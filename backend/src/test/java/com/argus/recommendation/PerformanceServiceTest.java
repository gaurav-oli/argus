package com.argus.recommendation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.argus.recommendation.GraduationService.GraduationSummary;
import com.argus.recommendation.PerformanceService.AccuracyView;
import com.argus.recommendation.PerformanceService.AttributionView;
import com.argus.recommendation.PerformanceService.Bin;
import com.argus.recommendation.PerformanceService.CalibrationView;
import com.argus.recommendation.RecommendationSignalRepository.AgentWeightAggregate;
import com.argus.recommendation.TradeDecision.Decision;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Agent 5 performance analytics: accuracy windows (9.2), attribution (9.3), calibration (9.4). */
class PerformanceServiceTest {

	private final PaperTradeRepository trades = mock(PaperTradeRepository.class);
	private final TradeDecisionRepository decisions = mock(TradeDecisionRepository.class);
	private final RecommendationRepository recommendations = mock(RecommendationRepository.class);
	private final RecommendationSignalRepository signals = mock(RecommendationSignalRepository.class);
	private final GraduationService graduation = mock(GraduationService.class);
	private final AdaptiveTuningService tuning = mock(AdaptiveTuningService.class);
	private final PerformanceService service =
			new PerformanceService(trades, decisions, recommendations, signals, graduation, tuning);

	// ---- 9.2 accuracy ----

	@Test
	void accuracyComputesWindowsAndFlagsSmallSamplesAsNotMeaningful() {
		when(trades.count()).thenReturn(8L);
		when(trades.countByWonTrue()).thenReturn(6L);
		when(trades.countByCreatedAtAfter(any())).thenReturn(4L);
		when(trades.countByWonTrueAndCreatedAtAfter(any())).thenReturn(3L);
		when(trades.findTop10ByOrderByIdDesc()).thenReturn(List.of(trade(true), trade(false), trade(true)));
		when(recommendations.count()).thenReturn(12L);
		when(decisions.countByDecision(Decision.TAKEN)).thenReturn(5L);
		when(decisions.countByDecision(Decision.DECLINED)).thenReturn(2L);
		when(graduation.summary()).thenReturn(new GraduationSummary("SHADOW", "UNPROVEN", false, 8, 75, 32));

		AccuracyView v = service.accuracy();

		assertEquals(75, v.all().winRatePct());          // 6/8
		assertFalse(v.all().statisticallyMeaningful());  // 8 < 20
		assertEquals(75, v.last30d().winRatePct());       // 3/4
		assertEquals(67, v.last10().winRatePct());        // 2/3 -> 66.7 rounds to 67
		assertEquals(12, v.totalIssued());
		assertEquals(5, v.taken());
		assertEquals(2, v.declined());
		assertEquals("SHADOW", v.graduationState());
	}

	@Test
	void accuracyHandlesNoTradesWithNullWinRate() {
		when(trades.count()).thenReturn(0L);
		when(trades.countByWonTrue()).thenReturn(0L);
		when(trades.findTop10ByOrderByIdDesc()).thenReturn(List.of());
		when(graduation.summary()).thenReturn(new GraduationSummary("SHADOW", "UNPROVEN", false, 0, 0, 40));

		AccuracyView v = service.accuracy();

		assertNull(v.all().winRatePct());
		assertEquals(0, v.all().trades());
	}

	// ---- 9.3 attribution ----

	@Test
	void attributionComputesPercentagesAndFlagsUnderperformers() {
		when(signals.aggregateByAgent()).thenReturn(List.of(
				agg("agent-1-news", "60", 30),
				agg("agent-7-calendar", "30", 15),
				agg("agent-3-web", "5", 5),
				agg("agent-2-social", "5", 5)));

		AttributionView v = service.attribution();

		assertEquals(4, v.agentCount());
		assertEquals("agent-1-news", v.agents().get(0).agent()); // sorted desc
		assertEquals(60.0, v.agents().get(0).contributionPct()); // 60/100
		assertFalse(v.agents().get(0).underperformer());
		// equal share = 25%; half = 12.5%. The 5% agents are underperformers.
		assertTrue(v.agents().get(3).underperformer());
	}

	// ---- 9.4 calibration ----

	@Test
	void calibrationBinsByStatedProbabilityAndMarksInsufficientBins() {
		// Two resolved trades for rec 1 (bull prob 0.85 -> bin 80-90): one win, one loss -> 50% hit, n=2 insufficient.
		when(trades.findAll()).thenReturn(List.of(tradeFor(true, 1L), tradeFor(false, 1L)));
		when(recommendations.findAllById(any())).thenReturn(List.of(bullishRec(1L, "0.85")));

		CalibrationView v = service.calibration();

		assertEquals(2, v.resolvedCount());
		Bin bin80 = v.bins().stream().filter(b -> b.lowPct() == 80).findFirst().orElseThrow();
		assertEquals(2, bin80.count());
		assertEquals(50, bin80.actualHitRatePct()); // 1/2
		assertFalse(bin80.sufficient());            // 2 < 5
		assertEquals(10, v.bins().size());
		// Brier: ((0.85−1)² + (0.85−0)²) / 2 = (0.0225 + 0.7225) / 2 = 0.3725 — worse than a coin flip,
		// exactly what an over-confident 85% call with a 50% hit rate deserves.
		assertEquals(0.3725, v.brierScore(), 1e-9);
	}

	@Test
	void calibrationBrierIsNullWithNoResolvedOutcomes() {
		when(trades.findAll()).thenReturn(List.of());
		when(recommendations.findAllById(any())).thenReturn(List.of());
		assertNull(service.calibration().brierScore());
	}

	// ---- helpers ----

	private static PaperTrade trade(boolean won) {
		return new PaperTrade(won, null);
	}

	private static PaperTrade tradeFor(boolean won, Long recId) {
		return new PaperTrade(won, recId);
	}

	private static AgentWeightAggregate agg(String agent, String totalWeight, long count) {
		return new AgentWeightAggregate() {
			public String getAgent() {
				return agent;
			}

			public BigDecimal getTotalWeight() {
				return new BigDecimal(totalWeight);
			}

			public long getSignalCount() {
				return count;
			}
		};
	}

	private static Recommendation bullishRec(Long id, String bullProbability) {
		double bull = Double.parseDouble(bullProbability);
		ProbabilityScore score = new ProbabilityScore(bull, 1 - bull, 0.5, bull, 1 - bull, List.of());
		Recommendation r = new Recommendation("ABCD", score, List.of(), null, "swing");
		setId(r, id);
		return r;
	}

	private static void setId(Recommendation r, Long id) {
		try {
			var f = Recommendation.class.getDeclaredField("id");
			f.setAccessible(true);
			f.set(r, id);
		} catch (ReflectiveOperationException e) {
			throw new IllegalStateException(e);
		}
	}
}
