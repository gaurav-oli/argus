package com.argus.recommendation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The dissent record (Fable 5 review item 8) — the pure per-agent stats the logic review shows the
 * LLM reviewer: how often an agent's signal pointed against the Analyst's final call, and how often
 * that dissent proved right. The propose→backtest→adopt pipeline itself is exercised elsewhere.
 */
class LogicReviewServiceTest {

	private static Recommendation rec(SignalDirection called, RecommendationSignal... signals) {
		Recommendation r = mock(Recommendation.class);
		when(r.getDirection()).thenReturn(called);
		when(r.getSignals()).thenReturn(List.of(signals));
		return r;
	}

	private static RecommendationSignal sig(String agent, SignalDirection dir) {
		return new RecommendationSignal(new AgentSignal(agent, dir, 1.0, "r"));
	}

	@Test
	void countsDissentsAndWhenTheDissenterWasRight() {
		// Trade 1: called BULLISH, went BEARISH (lost). agent-news dissented (bearish) → right.
		LogicReviewService.Eval lostCall = new LogicReviewService.Eval(
				rec(SignalDirection.BULLISH, sig("agent-news", SignalDirection.BEARISH),
						sig("agent-social", SignalDirection.BULLISH)),
				SignalDirection.BEARISH);
		// Trade 2: called BULLISH, went BULLISH (won). agent-news dissented again → wrong this time.
		LogicReviewService.Eval wonCall = new LogicReviewService.Eval(
				rec(SignalDirection.BULLISH, sig("agent-news", SignalDirection.BEARISH),
						sig("agent-social", SignalDirection.BULLISH)),
				SignalDirection.BULLISH);

		Map<String, int[]> stats = LogicReviewService.dissentStats(List.of(lostCall, wonCall));

		assertEquals(2, stats.get("agent-news")[0]); // dissented twice
		assertEquals(1, stats.get("agent-news")[1]); // right once
		assertFalse(stats.containsKey("agent-social")); // agreed with the call — never a dissenter
	}

	@Test
	void neutralSignalsAreNotDissent() {
		LogicReviewService.Eval e = new LogicReviewService.Eval(
				rec(SignalDirection.BULLISH, sig("agent-cal", SignalDirection.NEUTRAL)),
				SignalDirection.BEARISH);
		assertTrue(LogicReviewService.dissentStats(List.of(e)).isEmpty());
	}

	// ---- adoption decision (the actual bug: Brier moved but accuracy stayed exactly flat for
	// weeks in production, yet the old `>=` check let every one of those runs get adopted) ----

	@Test
	void calibrationOnlyChangeIsNotAdopted() {
		// Real production shape: Brier genuinely improves, accuracy doesn't move at all.
		var baseline = new LogicReviewService.Score(0.389, 0.3155);
		var proposed = new LogicReviewService.Score(0.389, 0.3034);

		assertTrue(LogicReviewService.brierImproved(baseline, proposed, 0.01));
		assertFalse(LogicReviewService.accuracyImproved(baseline, proposed));
	}

	@Test
	void accuracyRegressionIsNotAdoptedEvenIfBrierImproves() {
		var baseline = new LogicReviewService.Score(0.50, 0.32);
		var proposed = new LogicReviewService.Score(0.45, 0.28);

		assertTrue(LogicReviewService.brierImproved(baseline, proposed, 0.01));
		assertFalse(LogicReviewService.accuracyImproved(baseline, proposed));
	}

	@Test
	void genuineImprovementOnBothAxesIsAdopted() {
		var baseline = new LogicReviewService.Score(0.389, 0.3155);
		var proposed = new LogicReviewService.Score(0.417, 0.3034); // 14/36 -> 15/36 correct

		assertTrue(LogicReviewService.brierImproved(baseline, proposed, 0.01));
		assertTrue(LogicReviewService.accuracyImproved(baseline, proposed));
	}

	@Test
	void brierMarginBlocksNoiseLevelCalibrationWobble() {
		var baseline = new LogicReviewService.Score(0.417, 0.3155);
		var proposed = new LogicReviewService.Score(0.444, 0.3149); // real accuracy gain, trivial Brier wobble

		assertFalse(LogicReviewService.brierImproved(baseline, proposed, 0.01));
		assertTrue(LogicReviewService.accuracyImproved(baseline, proposed));
	}
}
