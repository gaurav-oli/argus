package com.argus.portfolio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.argus.model.ModelGateway;
import com.argus.model.ModelTier;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.junit.jupiter.api.Test;

/** The automatic "never fails" parser: tries local Gemma with a self-verification loop, escalates
 * to Haiku only as a last resort, and always returns something usable. */
class AdaptiveStatementParserTest {

	private final ModelGateway model = mock(ModelGateway.class);
	private final LlmStatementParser haiku = mock(LlmStatementParser.class);
	private final AdaptiveStatementParser parser = new AdaptiveStatementParser(model, haiku);

	private static final String ONE_HOLDING_JSON = """
			{"holdings":[{"ticker":"AAPL","companyName":"Apple Inc","shares":10,"bookValue":1000.00,"currency":"USD","account":"Cash"}],
			 "cash":[],"accounts":[]}""";

	private static final String TWO_HOLDINGS_JSON = """
			{"holdings":[{"ticker":"AAPL","companyName":"Apple Inc","shares":10,"bookValue":1000.00,"currency":"USD","account":"Cash"},
			              {"ticker":"MSFT","companyName":"Microsoft","shares":5,"bookValue":2000.00,"currency":"USD","account":"Cash"}],
			 "cash":[],"accounts":[]}""";

	/** A minimal real PDF (PDFBox) with no "total" line — so ImportConfidence's total-match check is
	 * simply skipped and only the shape of the result matters for these tests. */
	private static byte[] simplePdf() {
		try (PDDocument doc = new PDDocument()) {
			PDPage page = new PDPage();
			doc.addPage(page);
			try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
				cs.beginText();
				cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
				cs.newLineAtOffset(50, 700);
				cs.showText("Brokerage Statement");
				cs.endText();
			}
			java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
			doc.save(out);
			return out.toByteArray();
		}
		catch (java.io.IOException ex) {
			throw new java.io.UncheckedIOException(ex);
		}
	}

	@Test
	void confidentOnTheFirstLocalAttemptReturnsImmediately() {
		when(model.generate(anyString(), eq(ModelTier.BIG))).thenReturn(ONE_HOLDING_JSON);

		AdaptiveStatementParser.Outcome outcome = parser.parse(simplePdf());

		assertTrue(outcome.confident());
		assertEquals(1, outcome.result().holdings().size());
		verify(model, times(1)).generate(anyString(), eq(ModelTier.BIG));
		verify(haiku, times(0)).parse(any());
	}

	@Test
	void retriesLocallyWhenTheFirstAttemptFindsNothingThenSucceeds() {
		when(model.generate(anyString(), eq(ModelTier.BIG)))
				.thenReturn("{\"holdings\":[],\"cash\":[],\"accounts\":[]}") // attempt 1: nothing found
				.thenReturn(ONE_HOLDING_JSON); // attempt 2 (verification re-check): found it

		AdaptiveStatementParser.Outcome outcome = parser.parse(simplePdf());

		assertTrue(outcome.confident());
		assertEquals(1, outcome.result().holdings().size());
		verify(model, times(2)).generate(anyString(), eq(ModelTier.BIG));
	}

	@Test
	void theSecondAttemptsPromptIncludesThePreviousExtractionForVerification() {
		when(model.generate(anyString(), eq(ModelTier.BIG)))
				.thenReturn("{\"holdings\":[],\"cash\":[],\"accounts\":[]}")
				.thenReturn(ONE_HOLDING_JSON);

		parser.parse(simplePdf());

		org.mockito.ArgumentCaptor<String> prompts = org.mockito.ArgumentCaptor.forClass(String.class);
		verify(model, times(2)).generate(prompts.capture(), eq(ModelTier.BIG));
		assertTrue(prompts.getAllValues().get(1).contains("YOUR PREVIOUS EXTRACTION"),
				"the re-check prompt should show the model what it found before");
	}

	@Test
	void fallsBackToHaikuWhenLocalGemmaErrorsOutEveryAttempt() {
		when(model.generate(anyString(), eq(ModelTier.BIG))).thenThrow(new RuntimeException("Ollama unreachable"));
		when(haiku.parse(any())).thenReturn(
				new StatementParser.ParseResult(
						java.util.List.of(new ParsedHolding("AAPL", "Apple", new java.math.BigDecimal("10"),
								new java.math.BigDecimal("1000.00"), "USD", null, "Cash", false, java.util.List.of())),
						java.util.List.of(), java.util.List.of(), null));

		AdaptiveStatementParser.Outcome outcome = parser.parse(simplePdf());

		assertTrue(outcome.confident());
		assertEquals(1, outcome.result().holdings().size());
		verify(model, times(AdaptiveStatementParser.MAX_LOCAL_ATTEMPTS)).generate(anyString(), eq(ModelTier.BIG));
		verify(haiku, times(1)).parse(any());
	}

	@Test
	void neverConfidentStillReturnsTheBestAttemptFlaggedForReview() {
		// Every local attempt comes back empty (never confident); Haiku also can't do better.
		when(model.generate(anyString(), eq(ModelTier.BIG))).thenReturn("{\"holdings\":[],\"cash\":[],\"accounts\":[]}");
		when(haiku.parse(any())).thenReturn(new StatementParser.ParseResult(java.util.List.of(), java.util.List.of(),
				java.util.List.of(), null));

		AdaptiveStatementParser.Outcome outcome = parser.parse(simplePdf());

		assertFalse(outcome.confident());
		assertTrue(outcome.uncertainty() != null && !outcome.uncertainty().isBlank());
		assertTrue(outcome.result().message() != null, "a never-confident result must still carry a note, not silence");
	}

	@Test
	void whenEverythingFailsWithNoResultEverObtainedItThrowsRatherThanReturningNothing() {
		when(model.generate(anyString(), eq(ModelTier.BIG))).thenThrow(new RuntimeException("Ollama unreachable"));
		when(haiku.parse(any())).thenThrow(new RuntimeException("Haiku unavailable too"));

		assertThrows(RuntimeException.class, () -> parser.parse(simplePdf()));
	}

	@Test
	void whenNoAttemptEverReachesConfidenceTheMostCompleteOneIsStillKept() {
		// A stated total none of the attempts come close to, so every attempt (local or Haiku) fails
		// the confidence gate — but the one that found more should still be what comes back.
		byte[] pdfWithBigStatedTotal = pdfWithText("Total Portfolio Value: 50,000.00");
		when(model.generate(anyString(), eq(ModelTier.BIG)))
				.thenReturn(ONE_HOLDING_JSON)
				.thenReturn(TWO_HOLDINGS_JSON)
				.thenReturn(TWO_HOLDINGS_JSON);
		when(haiku.parse(any())).thenReturn(new StatementParser.ParseResult(java.util.List.of(), java.util.List.of(),
				java.util.List.of(), null));

		AdaptiveStatementParser.Outcome outcome = parser.parse(pdfWithBigStatedTotal);

		assertFalse(outcome.confident());
		assertEquals(2, outcome.result().holdings().size(), "the 2-holding attempt is more complete than the 1- or 0-holding ones");
	}

	private static byte[] pdfWithText(String text) {
		try (PDDocument doc = new PDDocument()) {
			PDPage page = new PDPage();
			doc.addPage(page);
			try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
				cs.beginText();
				cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
				cs.newLineAtOffset(50, 700);
				cs.showText(text);
				cs.endText();
			}
			java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
			doc.save(out);
			return out.toByteArray();
		}
		catch (java.io.IOException ex) {
			throw new java.io.UncheckedIOException(ex);
		}
	}
}
