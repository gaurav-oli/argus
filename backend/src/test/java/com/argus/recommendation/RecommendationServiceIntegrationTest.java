package com.argus.recommendation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.argus.TestcontainersConfiguration;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * Recommendation persistence + diagnostic against real Postgres (Story 6.2): the scored
 * recommendation and its full per-agent signal breakdown (conflicts included) round-trip.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class RecommendationServiceIntegrationTest {

	@Autowired
	RecommendationService service;

	@Autowired
	RecommendationRepository repo;

	@Autowired
	TradeDecisionRepository decisions;

	@BeforeEach
	void clean() {
		decisions.deleteAll(); // FK → recommendations; clear children first
		repo.deleteAll();
	}

	@Test
	void persistsRecommendationWithItsDiagnosticSignals() {
		List<AgentSignal> signals = List.of(
				new AgentSignal("agent-1-news", SignalDirection.BULLISH, 3, "positive coverage"),
				new AgentSignal("agent-7-calendar", SignalDirection.BEARISH, 1, "earnings risk"));

		Recommendation saved = service.create("AAPL", signals, new BigDecimal("250.00"), "3 months");

		Recommendation rec = service.diagnostic(saved.getId()).orElseThrow();
		assertEquals("AAPL", rec.getTicker());
		assertEquals(SignalDirection.BULLISH, rec.getDirection(), "bull weight 3 > bear 1");
		// Neutral-prior shrinkage (no more raw 3/4=0.75): (3 + 1.0*0.5) / (4 + 1.0) = 0.70.
		assertEquals(0, rec.getBullProbability().compareTo(new BigDecimal("0.7000")));
		assertEquals(RecommendationStatus.PENDING, rec.getStatus());

		// The diagnostic shows BOTH the bullish and the conflicting bearish signal.
		assertEquals(2, rec.getSignals().size());
		assertTrue(rec.getSignals().stream().anyMatch(s -> s.getDirection() == SignalDirection.BEARISH),
				"conflicting signal must be displayed, not hidden");
	}

	@Test
	void recentReturnsNewestFirst() {
		service.create("AAPL", List.of(new AgentSignal("a1", SignalDirection.BULLISH, 1, "x")), null, null);
		service.create("MSFT", List.of(new AgentSignal("a1", SignalDirection.BEARISH, 1, "y")), null, null);

		List<Recommendation> recent = service.recent();
		assertEquals(2, recent.size());
		assertEquals("MSFT", recent.get(0).getTicker(), "newest first");
	}
}
