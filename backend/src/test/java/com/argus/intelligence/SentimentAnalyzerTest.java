package com.argus.intelligence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.argus.model.ModelGateway;
import com.argus.model.ModelTier;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Small-model sentiment parsing + defensive fallbacks (Story 4.2). */
class SentimentAnalyzerTest {

	private final ModelGateway gateway = mock(ModelGateway.class);
	private final SentimentAnalyzer analyzer = new SentimentAnalyzer(gateway);

	private SentimentAnalysis analyze(String modelReply) {
		when(gateway.generate(anyString(), eq(ModelTier.SMALL))).thenReturn(modelReply);
		return analyzer.analyze("headline", "summary", List.of("AAPL"));
	}

	@Test
	void parsesWellFormedJson() {
		SentimentAnalysis a = analyze("{\"sentiment\":\"BULLISH\",\"score\":0.8,\"relevance\":0.9}");
		assertEquals(SentimentLabel.BULLISH, a.label());
		assertEquals(0.8, a.score(), 1e-9);
		assertEquals(0.9, a.relevance(), 1e-9);
	}

	@Test
	void toleratesSurroundingProseAndCodeFences() {
		SentimentAnalysis a = analyze("""
				Sure! Here is the assessment:
				```json
				{"sentiment":"BEARISH","score":-0.5,"relevance":0.4}
				```
				""");
		assertEquals(SentimentLabel.BEARISH, a.label());
		assertEquals(-0.5, a.score(), 1e-9);
	}

	@Test
	void clampsOutOfRangeScores() {
		SentimentAnalysis a = analyze("{\"sentiment\":\"BULLISH\",\"score\":4.2,\"relevance\":-3}");
		assertEquals(1.0, a.score(), 1e-9);
		assertEquals(0.0, a.relevance(), 1e-9);
	}

	@Test
	void unknownLabelBecomesNeutral() {
		SentimentAnalysis a = analyze("{\"sentiment\":\"ECSTATIC\",\"score\":0.1,\"relevance\":0.2}");
		assertEquals(SentimentLabel.NEUTRAL, a.label());
	}

	@Test
	void nonJsonReplyDegradesToNeutral() {
		// The dev mock returns a canned non-JSON string — must not blow up.
		SentimentAnalysis a = analyze("[dev-mock] Argus Model Gateway is alive.");
		assertEquals(SentimentLabel.NEUTRAL, a.label());
		assertEquals(0.0, a.score(), 1e-9);
		assertEquals(0.0, a.relevance(), 1e-9);
	}

	@Test
	void modelFailureDegradesToNeutral() {
		when(gateway.generate(anyString(), eq(ModelTier.SMALL))).thenThrow(new RuntimeException("timeout"));
		SentimentAnalysis a = analyzer.analyze("h", "s", List.of("AAPL"));
		assertEquals(SentimentLabel.NEUTRAL, a.label());
	}

	@Test
	void usesTheSmallTier() {
		analyze("{\"sentiment\":\"NEUTRAL\",\"score\":0,\"relevance\":0}");
		verify(gateway).generate(anyString(), eq(ModelTier.SMALL));
	}
}
