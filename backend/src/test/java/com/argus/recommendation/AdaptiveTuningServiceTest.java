package com.argus.recommendation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Phase B adaptive tuning: per-agent weight multipliers from realized hit rate, isotonic probability
 * calibration, and the guarantees that keep it safe — flag-off identity, cold-start no-op, clamps.
 */
class AdaptiveTuningServiceTest {

	private final SimulatedTradeRepository trades = mock(SimulatedTradeRepository.class);
	private final RecommendationRepository recs = mock(RecommendationRepository.class);
	private final AgentReliabilityRepository reliabilities = mock(AgentReliabilityRepository.class);
	private final ProbabilityCalibrationRepository calibrations = mock(ProbabilityCalibrationRepository.class);

	// Test posture: no shrink (shrinkK=0), minSample=2, gain=1, wide clamps — clean, assertable math.
	private AdaptiveTuningService service(boolean enabled) {
		AdaptiveTuningProperties props = new AdaptiveTuningProperties(
				enabled, 2, 0.0, 1.0, 0.25, 2.0, 0.5, 1.2, false);
		return new AdaptiveTuningService(trades, recs, reliabilities, calibrations, props);
	}

	// ---- pure isotonic (PAV) ----

	@Test
	void pavProducesMonotoneNonDecreasing() {
		double[] out = AdaptiveTuningService.pav(List.of(0.8, 0.4, 0.6), List.of(1.0, 1.0, 1.0));
		// 0.8,0.4,0.6 all violate → pooled to their equal-weight mean 0.6.
		assertEquals(0.6, out[0], 1e-9);
		assertEquals(0.6, out[1], 1e-9);
		assertEquals(0.6, out[2], 1e-9);
	}

	@Test
	void pavLeavesAlreadyMonotoneUntouched() {
		double[] out = AdaptiveTuningService.pav(List.of(0.3, 0.5, 0.9), List.of(1.0, 1.0, 1.0));
		assertEquals(0.3, out[0], 1e-9);
		assertEquals(0.5, out[1], 1e-9);
		assertEquals(0.9, out[2], 1e-9);
	}

	// ---- flag off = identity ----

	@Test
	void disabledIsAlwaysIdentity() {
		when(reliabilities.findAll()).thenReturn(List.of(reliability("agent-1-news", 1.5)));
		when(calibrations.findAll()).thenReturn(List.of(calBin(70, 0.55)));
		AdaptiveTuningService s = service(false);
		s.loadCache();

		assertEquals(1.0, s.weightMultiplier("agent-1-news"), 1e-9);
		assertEquals(0.72, s.calibrateDirectionalProbability(0.72), 1e-9); // unchanged
	}

	// ---- read side when enabled ----

	@Test
	void enabledAppliesLearnedMultiplierAndCalibration() {
		when(reliabilities.findAll()).thenReturn(List.of(reliability("agent-1-news", 1.4)));
		when(calibrations.findAll()).thenReturn(List.of(calBin(70, 0.58)));
		AdaptiveTuningService s = service(true);
		s.loadCache();

		assertEquals(1.4, s.weightMultiplier("agent-1-news"), 1e-9);
		assertEquals(1.0, s.weightMultiplier("agent-unseen"), 1e-9); // default identity
		assertEquals(0.58, s.calibrateDirectionalProbability(0.75), 1e-9); // bin 70 → 0.58
	}

	@Test
	void calibrationNeverFlipsTheCall() {
		when(reliabilities.findAll()).thenReturn(List.of());
		when(calibrations.findAll()).thenReturn(List.of(calBin(60, 0.30))); // wildly over-confident band
		AdaptiveTuningService s = service(true);
		s.loadCache();
		// A bullish 0.62 call whose band realized only 30% is floored to a coin flip, not reversed.
		assertEquals(0.5, s.calibrateDirectionalProbability(0.62), 1e-9);
	}

	// ---- recompute: attribution → multipliers ----

	@Test
	void recomputeRewardsRightAgentsAndPenalisesWrongOnes() {
		// Two winning bullish trades. "agent-good" called bullish (right); "agent-bad" called bearish (wrong).
		Recommendation r = bullishRecWithSignals();
		when(recs.findWithSignalsById(1L)).thenReturn(Optional.of(r));
		when(trades.findByStatus(SimulatedTrade.Status.CLOSED)).thenReturn(List.of(wonTrade(1L), wonTrade(1L)));

		service(true).recompute();

		ArgumentCaptor<AgentReliability> saved = ArgumentCaptor.forClass(AgentReliability.class);
		verify(reliabilities, org.mockito.Mockito.atLeastOnce()).save(saved.capture());
		double good = saved.getAllValues().stream().filter(a -> a.getAgent().equals("agent-good"))
				.findFirst().orElseThrow().getWeightMultiplier().doubleValue();
		double bad = saved.getAllValues().stream().filter(a -> a.getAgent().equals("agent-bad"))
				.findFirst().orElseThrow().getWeightMultiplier().doubleValue();
		assertEquals(1.5, good, 1e-9);  // hitRate 1.0 → 1 + (1.0-0.5) = 1.5
		assertEquals(0.5, bad, 1e-9);   // hitRate 0.0 → 1 + (0.0-0.5) = 0.5
	}

	@Test
	void recomputeColdStartLeavesMultiplierAtOne() {
		Recommendation r = bullishRecWithSignals();
		when(recs.findWithSignalsById(1L)).thenReturn(Optional.of(r));
		when(trades.findByStatus(SimulatedTrade.Status.CLOSED)).thenReturn(List.of(wonTrade(1L))); // n=1 < minSample 2

		service(true).recompute();

		ArgumentCaptor<AgentReliability> saved = ArgumentCaptor.forClass(AgentReliability.class);
		verify(reliabilities, org.mockito.Mockito.atLeastOnce()).save(saved.capture());
		assertTrue(saved.getAllValues().stream()
				.allMatch(a -> a.getWeightMultiplier().compareTo(BigDecimal.ONE) == 0));
	}

	// ---- helpers ----

	private static Recommendation bullishRecWithSignals() {
		Recommendation r = mock(Recommendation.class);
		when(r.getDirection()).thenReturn(SignalDirection.BULLISH);
		when(r.getBullProbability()).thenReturn(BigDecimal.valueOf(0.75));
		when(r.getSignals()).thenReturn(List.of(
				new RecommendationSignal(new AgentSignal("agent-good", SignalDirection.BULLISH, 1.0, "r")),
				new RecommendationSignal(new AgentSignal("agent-bad", SignalDirection.BEARISH, 1.0, "r"))));
		return r;
	}

	private static SimulatedTrade wonTrade(long recId) {
		SimulatedTrade t = new SimulatedTrade(recId, "AAPL", SignalDirection.BULLISH,
				BigDecimal.valueOf(100), BigDecimal.valueOf(50), 0);
		t.close(BigDecimal.valueOf(60)); // +20% → won
		return t;
	}

	private static AgentReliability reliability(String agent, double mult) {
		AgentReliability r = new AgentReliability(agent);
		r.update(10, 0.6, mult);
		return r;
	}

	private static ProbabilityCalibrationBin calBin(int low, double calibrated) {
		ProbabilityCalibrationBin b = new ProbabilityCalibrationBin(low);
		b.update(5, calibrated, calibrated);
		return b;
	}
}
