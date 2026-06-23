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
	void unanimousBullishGivesFullBullProbability() {
		ProbabilityScore s = engine.score(List.of(
				signal("a1", SignalDirection.BULLISH, 1),
				signal("a2", SignalDirection.BULLISH, 1),
				signal("a7", SignalDirection.BULLISH, 1)));
		assertEquals(1.0, s.bullProbability(), 1e-9);
		assertEquals(0.0, s.bearProbability(), 1e-9);
		assertTrue(s.confidence() > 0.6, "unanimous + decent coverage ⇒ high confidence");
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
		assertEquals(0.75, s.bullProbability(), 1e-9);
		assertEquals(3.0, s.bullScore(), 1e-9);
		assertEquals(1.0, s.bearScore(), 1e-9);
	}

	@Test
	void neutralSignalsAbstainFromTheProbabilityButAreTraceable() {
		ProbabilityScore s = engine.score(List.of(
				signal("a1", SignalDirection.BULLISH, 1),
				signal("a2", SignalDirection.NEUTRAL, 5)));
		assertEquals(1.0, s.bullProbability(), 1e-9, "neutral carries no directional weight");
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
