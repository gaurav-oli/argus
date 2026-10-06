package com.argus.recommendation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.argus.TestcontainersConfiguration;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

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

	@Autowired
	JdbcTemplate jdbc;

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

	@Test
	void recentDropsTickersTheLatestPassSkipped() {
		Recommendation dropped = service.create("MU", List.of(new AgentSignal("a1", SignalDirection.BULLISH, 1, "x")), null, null);
		service.create("AAPL", List.of(new AgentSignal("a1", SignalDirection.BULLISH, 1, "x")), null, null);
		// MU's last read is a pass older than the newest one: it left the universe and wasn't re-scored.
		ageBy(dropped.getId(), Duration.ofHours(12));

		List<String> tickers = service.recent().stream().map(Recommendation::getTicker).toList();
		assertEquals(List.of("AAPL"), tickers, "a call the latest pass didn't refresh is no longer current");
	}

	@Test
	void recentKeepsTheLastPassWhenNothingIsNewer() {
		Recommendation old = service.create("AAPL", List.of(new AgentSignal("a1", SignalDirection.BULLISH, 1, "x")), null, null);
		ageBy(old.getId(), Duration.ofHours(30)); // the host was down — show the last pass, not nothing

		assertEquals(1, service.recent().size());
	}

	@Test
	void callSinceIsTheStartOfTheUnbrokenCall() {
		List<AgentSignal> bull = List.of(new AgentSignal("a1", SignalDirection.BULLISH, 1, "x"));
		Recommendation before = service.create("AAPL", bull, null, null);
		Recommendation first = service.create("AAPL", bull, null, null);
		Recommendation latest = service.create("AAPL", bull, null, null);
		ageBy(before.getId(), Duration.ofHours(18));
		ageBy(first.getId(), Duration.ofHours(12));
		ageBy(latest.getId(), Duration.ofHours(6));
		jdbc.update("update recommendations set action = 'AVOID' where id = ?", before.getId());
		jdbc.update("update recommendations set action = 'BUY' where id in (?, ?)", first.getId(), latest.getId());

		Instant since = service.callSince(List.of(latest.getId())).get(latest.getId());
		assertEquals(repo.findById(first.getId()).orElseThrow().getCreatedAt().truncatedTo(ChronoUnit.MILLIS),
				since.truncatedTo(ChronoUnit.MILLIS), "the call began at the first same-direction read after the flip");
	}

	private void ageBy(Long id, Duration age) {
		jdbc.update("update recommendations set created_at = now() - make_interval(secs => ?) where id = ?",
				(double) age.toSeconds(), id);
	}
}
