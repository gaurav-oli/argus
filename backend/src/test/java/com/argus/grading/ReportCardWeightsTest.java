package com.argus.grading;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.argus.recommendation.AgentSignal;
import com.argus.recommendation.SignalDirection;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ReportCardWeightsTest {

	private final ReportCardService service = mock(ReportCardService.class);
	private final ReportCardWeights weights = new ReportCardWeights(service);
	private final AgentSignal bull = new AgentSignal("agent-8-macro", SignalDirection.BULLISH, 0.5, "rates");

	private void card(double mult, Double pinned, Map<String, Double> sits, Map<String, Integer> n, Boolean useful,
			double mean) {
		when(service.weight("agent-8-macro"))
				.thenReturn(Optional.of(new ReportCardService.Weight(mult, pinned, sits, n, useful, mean, "X")));
	}

	@Test
	void mutedAgentIsDropped() {
		card(0.0, null, Map.of(), Map.of(), null, 0);
		assertTrue(weights.apply(bull, false, false).isEmpty());
	}

	@Test
	void flippedAgentIsReadTheOtherWay() {
		card(-0.5, null, Map.of(), Map.of(), null, 0);
		AgentSignal s = weights.apply(bull, false, false).orElseThrow();
		assertEquals(SignalDirection.BEARISH, s.direction());
		assertEquals(0.25, s.weight(), 1e-9);
	}

	@Test
	void pinnedWeightWins() {
		card(0.0, 1.5, Map.of(), Map.of(), null, 0);
		assertEquals(0.75, weights.apply(bull, false, false).orElseThrow().weight(), 1e-9);
	}

	@Test
	void situationWeightUsedOnceItHasEvidence() {
		card(1.0, null, Map.of(ReportCardMath.RISK_OFF, 0.2), Map.of(ReportCardMath.RISK_OFF, 12), null, 0);
		assertEquals(0.1, weights.apply(bull, true, false).orElseThrow().weight(), 1e-9);
		assertEquals(0.5, weights.apply(bull, false, false).orElseThrow().weight(), 1e-9); // calm → overall weight
	}

	@Test
	void situationWithoutEvidenceFallsBackToOverall() {
		card(1.2, null, Map.of(ReportCardMath.RISK_OFF, 0.2), Map.of(ReportCardMath.RISK_OFF, 3), null, 0);
		assertEquals(0.6, weights.apply(bull, true, false).orElseThrow().weight(), 1e-9);
	}

	@Test
	void overconfidentAgentWeighedAtItsAverageStrength() {
		card(1.0, null, Map.of(), Map.of(), false, 0.3);
		assertEquals(0.3, weights.apply(bull, false, false).orElseThrow().weight(), 1e-9);
	}

	@Test
	void ungradedAgentUnchanged() {
		when(service.weight("agent-8-macro")).thenReturn(Optional.empty());
		assertEquals(bull, weights.apply(bull, false, false).orElseThrow());
	}
}
