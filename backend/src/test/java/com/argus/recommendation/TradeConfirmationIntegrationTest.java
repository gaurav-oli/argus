package com.argus.recommendation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.argus.TestcontainersConfiguration;
import com.argus.persona.Persona;
import com.argus.persona.PersonaStance;
import com.argus.persona.PersonaVerdict;
import com.argus.persona.PersonaVerdictRepository;
import com.argus.recommendation.TradeDecision.Decision;
import com.argus.recommendation.TradeDecision.Outcome;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * Trade confirmation + rationale snapshot against real Postgres (Story 6.7): the decision freezes a
 * snapshot of signals + reasoning, the recommendation status updates, and a taken trade's outcome
 * feeds the graduation win-rate without mutating the snapshot. Also covers Story 11.1 (Trade Journal)
 * additions: optional entry price/size, and real cached persona verdicts in the snapshot.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class TradeConfirmationIntegrationTest {

	@Autowired
	RecommendationService recommendations;

	@Autowired
	RecommendationRepository recRepo;

	@Autowired
	TradeConfirmationService confirmation;

	@Autowired
	TradeDecisionRepository decisions;

	@Autowired
	PaperTradeRepository trades;

	@Autowired
	AgentGraduationRepository graduation;

	@Autowired
	PersonaVerdictRepository personaVerdicts;

	@Autowired
	SimulatedTradeRepository simulatedTrades;

	@BeforeEach
	void clean() {
		decisions.deleteAll();
		trades.deleteAll();
		simulatedTrades.deleteAll();
		personaVerdicts.deleteAll();
		recRepo.deleteAll();
		graduation.save(new AgentGraduation());
	}

	private Recommendation aRecommendation() {
		return recommendations.create("AAPL", List.of(
				new AgentSignal("agent-1-news", SignalDirection.BULLISH, 2, "positive coverage"),
				new AgentSignal("agent-7-calendar", SignalDirection.BEARISH, 1, "earnings soon")),
				null, "review");
	}

	@Test
	void takenDecisionFreezesSnapshotAndUpdatesStatus() {
		Recommendation rec = aRecommendation();

		TradeDecision d = confirmation.confirm(rec.getId(), Decision.TAKEN, "I agree with the thesis", null, null);

		assertEquals(Decision.TAKEN, d.getDecision());
		assertTrue(d.getSnapshot().contains("positive coverage"), "snapshot freezes the signals");
		assertTrue(d.getSnapshot().contains("I agree with the thesis"), "snapshot freezes the reasoning");
		assertEquals(RecommendationStatus.TAKEN, recRepo.findById(rec.getId()).orElseThrow().getStatus());
	}

	@Test
	void takenOutcomeFeedsGraduationWinRate() {
		Recommendation rec = aRecommendation();
		TradeDecision d = confirmation.confirm(rec.getId(), Decision.TAKEN, "in", null, null);
		String snapshotBefore = d.getSnapshot();

		confirmation.recordOutcome(d.getId(), true);

		TradeDecision reloaded = decisions.findById(d.getId()).orElseThrow();
		assertEquals(Outcome.WIN, reloaded.getOutcome());
		assertEquals(snapshotBefore, reloaded.getSnapshot(), "outcome must not mutate the snapshot");
		assertEquals(1, trades.count(), "a taken trade's outcome is recorded for graduation");
		assertEquals(1, trades.countByWonTrue());
	}

	@Test
	void declinedOutcomeDoesNotAffectGraduation() {
		Recommendation rec = aRecommendation();
		TradeDecision d = confirmation.confirm(rec.getId(), Decision.DECLINED, "too risky", null, null);

		confirmation.recordOutcome(d.getId(), false);

		assertEquals(0, trades.count(), "declined trades don't count toward the win-rate");
	}

	@Test
	void entryPriceAndPositionSizePersistWhenProvided() {
		Recommendation rec = aRecommendation();

		TradeDecision d = confirmation.confirm(rec.getId(), Decision.TAKEN, "in",
				new BigDecimal("189.50"), new BigDecimal("25"));

		TradeDecision reloaded = decisions.findById(d.getId()).orElseThrow();
		assertEquals(0, new BigDecimal("189.50").compareTo(reloaded.getEntryPrice()));
		assertEquals(0, new BigDecimal("25").compareTo(reloaded.getPositionSize()));
	}

	@Test
	void entryPriceAndPositionSizeStayNullWhenOmitted() {
		Recommendation rec = aRecommendation();

		TradeDecision d = confirmation.confirm(rec.getId(), Decision.DECLINED, "too risky", null, null);

		TradeDecision reloaded = decisions.findById(d.getId()).orElseThrow();
		assertNull(reloaded.getEntryPrice());
		assertNull(reloaded.getPositionSize());
	}

	@Test
	void snapshotCapturesCachedPersonaVerdicts() {
		Recommendation rec = aRecommendation();
		personaVerdicts.save(new PersonaVerdict(rec.getId(), Persona.BUFFETT, PersonaStance.AGREE,
				"Durable moat, fair price."));
		personaVerdicts.save(new PersonaVerdict(rec.getId(), Persona.DEVILS_ADVOCATE, PersonaStance.CAUTION,
				"Watch the earnings date."));

		TradeDecision d = confirmation.confirm(rec.getId(), Decision.TAKEN, "in", null, null);

		assertTrue(d.getSnapshot().contains("Durable moat, fair price."),
				"snapshot must freeze real cached persona verdicts, not the empty Epic-7 seam");
		assertTrue(d.getSnapshot().contains("Watch the earnings date."));
		assertTrue(d.getSnapshot().contains("BUFFETT"));
	}

	@Test
	void snapshotPersonaVerdictsAreEmptyWhenNoneCached() {
		Recommendation rec = aRecommendation();

		TradeDecision d = confirmation.confirm(rec.getId(), Decision.TAKEN, "in", null, null);

		assertTrue(d.getSnapshot().contains("\"personaVerdicts\":[]"),
				"no cached verdicts must freeze as an empty list, not trigger generation or fail");
	}

	// ---- recordAgentDecision: the Investor persona's own Taken/Declined (Trade Journal, regret) ----

	@Test
	void agentDecisionIsTaggedWithAgentSource() {
		Recommendation rec = aRecommendation();

		confirmation.recordAgentDecision(rec.getId(), Decision.TAKEN);

		TradeDecision d = decisions.findByRecommendationId(rec.getId()).get(0);
		assertEquals(TradeDecision.Source.AGENT, d.getSource());
		assertEquals(Decision.TAKEN, d.getDecision());
		assertEquals(RecommendationStatus.TAKEN, recRepo.findById(rec.getId()).orElseThrow().getStatus());
	}

	@Test
	void humanConfirmIsTaggedWithUserSource() {
		Recommendation rec = aRecommendation();

		TradeDecision d = confirmation.confirm(rec.getId(), Decision.TAKEN, "in", null, null);

		assertEquals(TradeDecision.Source.USER, d.getSource());
	}

	@Test
	void agentDecisionNeverOverwritesAnExistingOne() {
		Recommendation rec = aRecommendation();
		confirmation.confirm(rec.getId(), Decision.DECLINED, "too risky", null, null);

		confirmation.recordAgentDecision(rec.getId(), Decision.TAKEN);

		assertEquals(1, decisions.findByRecommendationId(rec.getId()).size(),
				"the Investor must not overwrite a decision that already exists");
		assertEquals(Decision.DECLINED, decisions.findByRecommendationId(rec.getId()).get(0).getDecision());
	}

	@Test
	void agentDecisionIsIdempotent() {
		Recommendation rec = aRecommendation();

		confirmation.recordAgentDecision(rec.getId(), Decision.TAKEN);
		confirmation.recordAgentDecision(rec.getId(), Decision.TAKEN);

		assertEquals(1, decisions.findByRecommendationId(rec.getId()).size());
	}

	// ---- backfillAgentDecisions: historical recommendations that predate this wiring ----

	@Test
	void backfillClassifiesTakenFromAnExistingPaperTrade() {
		Recommendation rec = aRecommendation();
		simulatedTrades.save(new SimulatedTrade(rec.getId(), "AAPL", SignalDirection.BULLISH,
				new BigDecimal("100"), new BigDecimal("50"), 30, null));

		confirmation.backfillAgentDecisions();

		TradeDecision d = decisions.findByRecommendationId(rec.getId()).get(0);
		assertEquals(Decision.TAKEN, d.getDecision());
		assertEquals(TradeDecision.Source.AGENT, d.getSource());
	}

	@Test
	void backfillClassifiesTakenFromAReaffirmedThesisWithNoDirectTradeRow() {
		// The exact gap found against real production data: a repeat recommendation that only
		// re-affirmed an already-open (ticker, direction) thesis never gets its own simulated_trades
		// row — the leg keeps the id of whichever recommendation opened it FIRST. That still means the
		// Investor was genuinely acting on this later call too, so it must backfill as TAKEN.
		Recommendation opener = aRecommendation(); // AAPL BULLISH — opens the thesis
		simulatedTrades.save(new SimulatedTrade(opener.getId(), "AAPL", SignalDirection.BULLISH,
				new BigDecimal("100"), new BigDecimal("50"), 30, null));
		Recommendation reaffirmed = aRecommendation(); // a later AAPL BULLISH call, no trade row of its own

		confirmation.backfillAgentDecisions();

		TradeDecision d = decisions.findByRecommendationId(reaffirmed.getId()).get(0);
		assertEquals(Decision.TAKEN, d.getDecision());
		assertEquals(TradeDecision.Source.AGENT, d.getSource());
	}

	@Test
	void backfillLeavesGenuinelyUndecidedRecommendationsAlone() {
		// Non-neutral (real directional call) but no paper trade exists at all for this ticker/direction
		// — the Investor never got to price it (e.g. an unpriced ticker at the time). Not a decision —
		// must stay untouched.
		Recommendation rec = aRecommendation();

		confirmation.backfillAgentDecisions();

		assertTrue(decisions.findByRecommendationId(rec.getId()).isEmpty());
	}
}
