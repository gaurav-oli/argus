package com.argus.recommendation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Auditable, LLM-free probability scoring (Story 6.1, GAP-6). */
class ProbabilityScoringEngineTest {

	private final ProbabilityScoringEngine engine = new ProbabilityScoringEngine();

	private static AgentSignal signal(String agent, SignalDirection dir, double weight) {
		return new AgentSignal(agent, dir, weight, "because");
	}

	@Test
	void unanimousBullishIsStrongButShyOfCertainty() {
		ProbabilityScore s = engine.score(List.of(
				signal("a1", SignalDirection.BULLISH, 1),
				signal("a2", SignalDirection.BULLISH, 1),
				signal("a7", SignalDirection.BULLISH, 1)));
		// (3 + 0.5) / (3 + 1) = 0.875 — strong, but the neutral prior keeps it off a false 100%.
		assertEquals(0.875, s.bullProbability(), 1e-9);
		assertEquals(0.125, s.bearProbability(), 1e-9);
		assertTrue(s.confidence() > 0.6, "unanimous + decent coverage ⇒ high confidence");
	}

	@Test
	void aSingleThinSignalOnlyNudgesOffFiftyFifty() {
		ProbabilityScore s = engine.score(List.of(signal("a1", SignalDirection.BULLISH, 1)));
		// (1 + 0.5) / (1 + 1) = 0.75 — one mid-weight signal, not "100% bull".
		assertEquals(0.75, s.bullProbability(), 1e-9);
		// A faint signal barely moves it: (0.2 + 0.5) / (0.2 + 1) ≈ 0.583.
		assertEquals(0.5833333, engine.score(List.of(signal("a1", SignalDirection.BULLISH, 0.2)))
				.bullProbability(), 1e-6);
	}

	@Test
	void conflictingEqualWeightsAreFiftyFiftyWithZeroConfidence() {
		ProbabilityScore s = engine.score(List.of(
				signal("a1", SignalDirection.BULLISH, 2),
				signal("a4", SignalDirection.BEARISH, 2)));
		assertEquals(0.5, s.bullProbability(), 1e-9);
		assertEquals(0.0, s.confidence(), 1e-9, "perfect disagreement ⇒ no confidence");
	}

	@Test
	void probabilityIsWeightProportional() {
		ProbabilityScore s = engine.score(List.of(
				signal("a1", SignalDirection.BULLISH, 3),
				signal("a4", SignalDirection.BEARISH, 1)));
		// (3 + 0.5) / (4 + 1) = 0.70 — bull-leaning in proportion to the weights, prior-shrunk.
		assertEquals(0.7, s.bullProbability(), 1e-9);
		assertEquals(3.0, s.bullScore(), 1e-9);
		assertEquals(1.0, s.bearScore(), 1e-9);
	}

	@Test
	void neutralSignalsAbstainFromTheProbabilityButAreTraceable() {
		ProbabilityScore s = engine.score(List.of(
				signal("a1", SignalDirection.BULLISH, 1),
				signal("a2", SignalDirection.NEUTRAL, 5)));
		// Neutral adds no directional weight, so this is the lone-bull case: (1 + 0.5) / (1 + 1) = 0.75.
		assertEquals(0.75, s.bullProbability(), 1e-9, "neutral carries no directional weight");
		assertEquals(0.0, s.bearScore(), 1e-9);
		assertEquals(2, s.contributions().size(), "every input signal is in the audit trail");
	}

	@Test
	void noSignalsIsMaximumUncertainty() {
		ProbabilityScore s = engine.score(List.of());
		assertEquals(0.5, s.bullProbability(), 1e-9);
		assertEquals(0.0, s.confidence(), 1e-9);
		assertTrue(s.contributions().isEmpty());
	}

	@Test
	void contributionsTraceEverySignedWeight() {
		ProbabilityScore s = engine.score(List.of(
				signal("a1", SignalDirection.BULLISH, 2),
				signal("a4", SignalDirection.BEARISH, 1.5)));
		assertEquals(2.0, s.contributions().get(0).signedWeight(), 1e-9);
		assertEquals(-1.5, s.contributions().get(1).signedWeight(), 1e-9);
	}

	@Test
	void negativeWeightIsClampedToZero() {
		AgentSignal s = new AgentSignal("a1", SignalDirection.BULLISH, -5, "bad weight");
		assertEquals(0.0, s.weight(), 1e-9);
	}

	@Test
	void broaderConsensusRaisesConfidence() {
		List<AgentSignal> few = List.of(signal("a1", SignalDirection.BULLISH, 1));
		List<AgentSignal> many = List.of(
				signal("a1", SignalDirection.BULLISH, 1), signal("a2", SignalDirection.BULLISH, 1),
				signal("a3", SignalDirection.BULLISH, 1), signal("a4", SignalDirection.BULLISH, 1),
				signal("a5", SignalDirection.BULLISH, 1), signal("a6", SignalDirection.BULLISH, 1),
				signal("a7", SignalDirection.BULLISH, 1));
		assertTrue(engine.score(many).confidence() > engine.score(few).confidence(),
				"7 agreeing agents should beat 1 on confidence");
	}
}
