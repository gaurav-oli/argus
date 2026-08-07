package com.argus.recommendation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.argus.TestcontainersConfiguration;
import com.argus.recommendation.JournalService.JournalDetailView;
import com.argus.recommendation.JournalService.JournalEntryView;
import com.argus.recommendation.TradeDecision.Decision;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * Trade Journal read paths (Story 11.1, F22) against real Postgres: list ordering, outcome
 * derivation that must never disagree with {@link PerformanceService#regret()}'s own figures, and
 * the detail view's full frozen-snapshot content.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class JournalServiceIntegrationTest {

	@Autowired
	RecommendationService recommendations;

	@Autowired
	RecommendationRepository recRepo;

	@Autowired
	TradeConfirmationService confirmation;

	@Autowired
	TradeDecisionRepository decisions;

	@Autowired
	SimulatedTradeRepository simulatedTrades;

	@Autowired
	AgentGraduationRepository graduation;

	@Autowired
	JournalService journal;

	@BeforeEach
	void clean() {
		decisions.deleteAll();
		simulatedTrades.deleteAll();
		recRepo.deleteAll();
		graduation.save(new AgentGraduation());
	}

	private Recommendation aRecommendation(String ticker) {
		return recommendations.create(ticker, List.of(
				new AgentSignal("agent-1-news", SignalDirection.BULLISH, 2, "positive coverage"),
				new AgentSignal("agent-7-calendar", SignalDirection.BEARISH, 1, "earnings soon")),
				null, "review");
	}

	@Test
	void listIsMostRecentFirst() {
		Recommendation a = aRecommendation("AAPL");
		confirmation.confirm(a.getId(), Decision.TAKEN, "first", null, null);
		Recommendation b = aRecommendation("MSFT");
		confirmation.confirm(b.getId(), Decision.DECLINED, "second", null, null);

		List<JournalEntryView> entries = journal.list();

		assertEquals(2, entries.size());
		assertEquals("MSFT", entries.get(0).ticker(), "most recently decided must come first");
		assertEquals("AAPL", entries.get(1).ticker());
	}

	@Test
	void outcomeIsPendingWithNoClosedPaperLeg() {
		Recommendation rec = aRecommendation("AAPL");
		confirmation.confirm(rec.getId(), Decision.TAKEN, "in", null, null);

		JournalEntryView entry = journal.list().get(0);

		assertEquals("PENDING", entry.outcome());
	}

	@Test
	void outcomeMatchesPerformanceServiceRegretDerivation() {
		Recommendation rec = aRecommendation("AAPL");
		TradeDecision d = confirmation.confirm(rec.getId(), Decision.TAKEN, "in",
				new BigDecimal("100.00"), new BigDecimal("10"));
		SimulatedTrade trade = new SimulatedTrade(rec.getId(), "AAPL", SignalDirection.BULLISH,
				new BigDecimal("100"), new BigDecimal("100.00"), 7, null);
		trade.close(new BigDecimal("110.00"), null);
		simulatedTrades.save(trade);

		JournalEntryView entry = journal.list().get(0);
		Optional<JournalDetailView> detail = journal.detail(d.getId());

		assertEquals("WIN", entry.outcome(), "a positive closed-leg return must read as a win");
		assertTrue(detail.isPresent());
		assertEquals(entry.outcome(), detail.get().outcome(), "list and detail must never disagree");
		assertEquals(entry.outcomeReturnPct(), detail.get().outcomeReturnPct());
	}

	@Test
	void detailViewReturnsFullSnapshotAndEntryDetails() {
		Recommendation rec = aRecommendation("AAPL");
		TradeDecision d = confirmation.confirm(rec.getId(), Decision.TAKEN, "I agree with the thesis",
				new BigDecimal("189.50"), new BigDecimal("25"));

		JournalDetailView detail = journal.detail(d.getId()).orElseThrow();

		assertEquals("AAPL", detail.ticker());
		assertEquals("I agree with the thesis", detail.reasoning());
		assertEquals(0, new BigDecimal("189.50").compareTo(detail.entryPrice()));
		assertEquals(0, new BigDecimal("25").compareTo(detail.positionSize()));
		assertEquals(2, detail.signals().size());
		assertTrue(detail.signals().stream().anyMatch(s -> "agent-1-news".equals(s.agent())));
		assertTrue(detail.personaVerdicts().isEmpty(), "no persona verdicts were cached for this recommendation");
	}

	@Test
	void detailReturnsEmptyForUnknownDecision() {
		assertFalse(journal.detail(999_999L).isPresent());
	}

	@Test
	void listIsCappedAtTheMostRecent100() {
		// The journal now grows continuously (every recommendation the Investor persona acts on records
		// one) — an unbounded fetch was shipping and rendering the entire history on every page load.
		Recommendation last = null;
		for (int i = 0; i < 105; i++) {
			last = aRecommendation("T" + i);
			confirmation.confirm(last.getId(), Decision.TAKEN, "auto", null, null);
		}

		List<JournalEntryView> entries = journal.list();

		assertEquals(100, entries.size());
		assertEquals(last.getTicker(), entries.get(0).ticker(), "still most-recent-first within the cap");
	}
}
