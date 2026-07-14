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
}
