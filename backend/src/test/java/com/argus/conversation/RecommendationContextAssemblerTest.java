package com.argus.conversation;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.argus.portfolio.HealthDeduction;
import com.argus.portfolio.HealthScoreResult;
import com.argus.portfolio.PortfolioSnapshot;
import com.argus.portfolio.PositionValue;
import com.argus.recommendation.AgentSignal;
import com.argus.recommendation.ProbabilityScore;
import com.argus.recommendation.Recommendation;
import com.argus.recommendation.SignalDirection;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The grounding block carries the rec's diagnostic + the portfolio/health context (Story 7.1, AC #1). */
class RecommendationContextAssemblerTest {

	private final RecommendationContextAssembler assembler = new RecommendationContextAssembler();

	private static Recommendation recommendation() {
		ProbabilityScore score = new ProbabilityScore(0.70, 0.30, 0.65, 4.0, 1.0, List.of());
		return new Recommendation("AAPL", score, List.of(
				new AgentSignal("agent-1-news", SignalDirection.BULLISH, 3, "Strong positive sentiment"),
				new AgentSignal("agent-7-calendar", SignalDirection.BEARISH, 1, "Earnings in 2 days")),
				new BigDecimal("200"), "3 months");
	}

	private static PortfolioSnapshot portfolio() {
		PositionValue aapl = new PositionValue("AAPL", "Apple Inc.", new BigDecimal("10"),
				new BigDecimal("190"), new BigDecimal("1900"), new BigDecimal("1500"), new BigDecimal("400"),
				new BigDecimal("26.67"), null, null, null, "USD", new BigDecimal("2600"), new BigDecimal("550"),
				new BigDecimal("40"), false, Instant.now());
		return new PortfolioSnapshot(new BigDecimal("6500"), new BigDecimal("5000"), new BigDecimal("1500"),
				false, Instant.now(), List.of(aapl));
	}

	private static HealthScoreResult health() {
		return new HealthScoreResult(82, List.of(new HealthDeduction("concentration_single",
				"Single-name concentration", 12, "AAPL is 40% of your portfolio", "Trim AAPL")), Instant.now());
	}

	@Test
	void includesRecommendationSignalsAndProbabilities() {
		String block = assembler.assemble(recommendation(), portfolio(), health());

		assertTrue(block.contains("RECOMMENDATION: AAPL"), block);
		assertTrue(block.contains("confidence 65%"), block);
		assertTrue(block.contains("bull 70%"), block);
		assertTrue(block.contains("agent-1-news"), block);
		assertTrue(block.contains("Strong positive sentiment"), block);
		assertTrue(block.contains("agent-7-calendar"), block);
	}

	@Test
	void includesPortfolioAndHealthContext() {
		String block = assembler.assemble(recommendation(), portfolio(), health());

		assertTrue(block.contains("Apple Inc."), block);
		assertTrue(block.contains("40% of portfolio"), block);
		assertTrue(block.contains("Health score: 82/100"), block);
		assertTrue(block.contains("Single-name concentration"), block);
	}
}
