package com.argus.technical;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.argus.intelligence.MacroRelevanceTagger;
import com.argus.intelligence.NewsArticle;
import com.argus.intelligence.NewsArticleRepository;
import com.argus.model.ModelGateway;
import com.argus.model.ModelTier;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Agent 11's cause classification: prompt gating, defensive JSON parsing, safe-empty on any
 * failure — never guesses (Epic: technical analysis + cause-of-move classification). */
class CauseClassificationServiceTest {

	private final NewsArticleRepository news = mock(NewsArticleRepository.class);
	private final ModelGateway gateway = mock(ModelGateway.class);
	private final CauseClassificationService service = new CauseClassificationService(news, gateway);

	private static NewsArticle article(String headline) {
		return new NewsArticle("rss", "id" + Math.random(), "u", headline, "s", Instant.now(),
				new String[] {"AAPL"});
	}

	private void noCompanyNews() {
		when(news.findAnalyzedForTicker(eq("AAPL"), any())).thenReturn(List.of());
	}

	private void noMacroNews() {
		when(news.findAnalyzedForTicker(eq(MacroRelevanceTagger.MACRO_TAG), any())).thenReturn(List.of());
	}

	@Test
	void neverCallsTheModelWhenThereIsNoNewsToReasonFrom() {
		noCompanyNews();
		noMacroNews();

		Optional<CauseClassificationService.Classification> result = service.classify("AAPL", -10.0);

		assertTrue(result.isEmpty());
		verify(gateway, never()).generate(anyString(), any());
	}

	@Test
	void parsesAWellFormedClassification() {
		when(news.findAnalyzedForTicker(eq("AAPL"), any())).thenReturn(List.of());
		when(news.findAnalyzedForTicker(eq(MacroRelevanceTagger.MACRO_TAG), any()))
				.thenReturn(List.of(article("US strikes escalate Middle East tensions")));
		when(gateway.generate(anyString(), eq(ModelTier.BIG))).thenReturn(
				"{\"cause\":\"MACRO_EXTERNAL\",\"temporary\":true,\"confidence\":0.85,"
						+ "\"reasoning\":\"Broad geopolitical selloff, not company-specific.\"}");

		Optional<CauseClassificationService.Classification> result = service.classify("AAPL", -10.0);

		assertTrue(result.isPresent());
		assertEquals(CauseClassificationService.Cause.MACRO_EXTERNAL, result.get().cause());
		assertTrue(result.get().temporary());
		assertEquals(0.85, result.get().confidence(), 0.001);
	}

	@Test
	void toleratesCodeFencesAndSurroundingProse() {
		noMacroNews();
		when(news.findAnalyzedForTicker(eq("AAPL"), any())).thenReturn(List.of(article("Earnings miss")));
		when(gateway.generate(anyString(), eq(ModelTier.BIG))).thenReturn(
				"Here is my analysis:\n```json\n{\"cause\":\"COMPANY_SPECIFIC\",\"temporary\":false,"
						+ "\"confidence\":0.9,\"reasoning\":\"Real earnings miss.\"}\n```\nHope that helps!");

		Optional<CauseClassificationService.Classification> result = service.classify("AAPL", -12.0);

		assertTrue(result.isPresent());
		assertEquals(CauseClassificationService.Cause.COMPANY_SPECIFIC, result.get().cause());
	}

	@Test
	void unrecognizedCauseValueNeverGuesses() {
		noMacroNews();
		when(news.findAnalyzedForTicker(eq("AAPL"), any())).thenReturn(List.of(article("Some news")));
		when(gateway.generate(anyString(), eq(ModelTier.BIG))).thenReturn(
				"{\"cause\":\"MAYBE_BOTH\",\"temporary\":true,\"confidence\":0.7,\"reasoning\":\"x\"}");

		assertTrue(service.classify("AAPL", -10.0).isEmpty());
	}

	@Test
	void outOfRangeConfidenceIsRejected() {
		noMacroNews();
		when(news.findAnalyzedForTicker(eq("AAPL"), any())).thenReturn(List.of(article("Some news")));
		when(gateway.generate(anyString(), eq(ModelTier.BIG))).thenReturn(
				"{\"cause\":\"MACRO_EXTERNAL\",\"temporary\":true,\"confidence\":1.5,\"reasoning\":\"x\"}");

		assertTrue(service.classify("AAPL", -10.0).isEmpty());
	}

	@Test
	void malformedResponseIsHandledGracefully() {
		noMacroNews();
		when(news.findAnalyzedForTicker(eq("AAPL"), any())).thenReturn(List.of(article("Some news")));
		when(gateway.generate(anyString(), eq(ModelTier.BIG))).thenReturn("not json at all");

		assertTrue(service.classify("AAPL", -10.0).isEmpty());
	}

	@Test
	void aFailingModelCallNeverThrows() {
		noMacroNews();
		when(news.findAnalyzedForTicker(eq("AAPL"), any())).thenReturn(List.of(article("Some news")));
		when(gateway.generate(anyString(), eq(ModelTier.BIG))).thenThrow(new RuntimeException("model down"));

		assertTrue(service.classify("AAPL", -10.0).isEmpty());
	}

	@Test
	void usesTheFreeLocalTierNotThePaidEscalation() {
		noMacroNews();
		when(news.findAnalyzedForTicker(eq("AAPL"), any())).thenReturn(List.of(article("Some news")));
		when(gateway.generate(anyString(), eq(ModelTier.BIG))).thenReturn(
				"{\"cause\":\"UNCLEAR\",\"temporary\":false,\"confidence\":0.5,\"reasoning\":\"x\"}");

		service.classify("AAPL", -10.0);

		verify(gateway).generate(anyString(), eq(ModelTier.BIG));
		verify(gateway, never()).escalate(anyString());
	}
}
