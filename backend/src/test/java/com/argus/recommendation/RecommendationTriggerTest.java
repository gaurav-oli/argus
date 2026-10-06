package com.argus.recommendation;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.argus.calendar.EarningsQuietPeriodService;
import com.argus.calendar.QuietPeriodStatus;
import com.argus.intelligence.KnownUniverse;
import com.argus.deepanalysis.DeepAnalysisService;
import com.argus.regime.MarketRegime;
import com.argus.regime.MarketRegimeService;
import com.argus.regime.Sector;
import com.argus.regime.SectorClassifier;
import com.argus.technical.ChartStudyService;
import com.argus.technical.LivePriceService;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Agent 5 trigger gates: FROZEN and quiet period suppress; otherwise it produces a card (Story 6.4). */
class RecommendationTriggerTest {

	private final AgentSignalGatherer gatherer = mock(AgentSignalGatherer.class);
	private final RecommendationService recommendations = mock(RecommendationService.class);
	private final GraduationService graduation = mock(GraduationService.class);
	private final EarningsQuietPeriodService quietPeriod = mock(EarningsQuietPeriodService.class);
	private final KnownUniverse universe = mock(KnownUniverse.class);
	private final PaperInvestorService investor = mock(PaperInvestorService.class);
	private final RecommendationPolicy policy = mock(RecommendationPolicy.class);
	private final SectorClassifier sectors = mock(SectorClassifier.class);
	private final MarketRegimeService regimes = mock(MarketRegimeService.class);
	private final LivePriceService prices = mock(LivePriceService.class);
	private final ChartStudyService charts = mock(ChartStudyService.class);
	private final DeepAnalysisService deepAnalyses = mock(DeepAnalysisService.class);
	private final RecommendationTrigger trigger = new RecommendationTrigger(
			gatherer, recommendations, graduation, quietPeriod, universe, investor, policy, sectors, regimes,
			prices, charts, deepAnalyses);

	private final AgentSignal aSignal = new AgentSignal("agent-1-news", SignalDirection.BULLISH, 1, "x");

	private void notFrozenClearWithSignals() {
		lenient().when(graduation.currentState()).thenReturn(GraduationState.ACTIVE);
		lenient().when(quietPeriod.statusFor(anyString())).thenReturn(QuietPeriodStatus.clear());
		lenient().when(gatherer.gather(anyString())).thenReturn(List.of(aSignal));
		lenient().when(sectors.sectorOf(anyString())).thenReturn(Sector.TECHNOLOGY);
		lenient().when(regimes.current()).thenReturn(MarketRegime.unavailable());
		lenient().when(regimes.moveOf(anyString())).thenReturn(java.util.Optional.empty());
		lenient().when(prices.livePrice(anyString())).thenReturn(java.util.Optional.empty());
		lenient().when(charts.studyFor(anyString())).thenReturn(java.util.Optional.empty());
		lenient().when(deepAnalyses.viewFor(anyString())).thenReturn(java.util.Optional.empty());
		Recommendation buy = recWithAction(RecommendationAction.BUY); // built first: no mock creation mid-stubbing
		lenient().when(recommendations.create(anyString(), any(), any(), anyString(), anyString())).thenReturn(buy);
	}

	@Test
	void producesRecommendationWhenGatesAllow() {
		notFrozenClearWithSignals();
		trigger.trigger("AAPL");
		verify(recommendations).create(anyString(), any(), any(), anyString(), anyString());
	}

	@Test
	void frozenSuppressesAllRecommendations() {
		when(graduation.currentState()).thenReturn(GraduationState.FROZEN);
		assertTrue(trigger.trigger("AAPL").isEmpty());
		verify(recommendations, never()).create(anyString(), any(), any(), anyString(), anyString());
	}

	@Test
	void quietPeriodSuppressesTheProbabilityCard() {
		when(graduation.currentState()).thenReturn(GraduationState.ACTIVE);
		when(quietPeriod.statusFor("AAPL"))
				.thenReturn(new QuietPeriodStatus(QuietPeriodStatus.Status.QUIET, LocalDate.now(), 1));
		assertTrue(trigger.trigger("AAPL").isEmpty());
		verify(recommendations, never()).create(anyString(), any(), any(), anyString(), anyString());
	}

	@Test
	void noSignalsProducesNothing() {
		when(graduation.currentState()).thenReturn(GraduationState.ACTIVE);
		when(quietPeriod.statusFor(anyString())).thenReturn(QuietPeriodStatus.clear());
		when(gatherer.gather("AAPL")).thenReturn(List.of());
		assertTrue(trigger.trigger("AAPL").isEmpty());
		verify(recommendations, never()).create(anyString(), any(), any(), anyString(), anyString());
	}

	private static Recommendation recWithAction(RecommendationAction action) {
		Recommendation r = mock(Recommendation.class);
		lenient().when(r.getAction()).thenReturn(action);
		lenient().when(r.getTicker()).thenReturn("AAPL");
		lenient().when(r.getConvictionScore()).thenReturn(70);
		lenient().when(r.getHoldDays()).thenReturn(30);
		return r;
	}

	@Test
	void actionableCallOpensAPaperTrade() {
		notFrozenClearWithSignals();
		Recommendation buy = recWithAction(RecommendationAction.BUY);
		when(recommendations.create(anyString(), any(), any(), anyString(), anyString())).thenReturn(buy);

		trigger.trigger("AAPL");

		verify(investor).open(buy);
	}

	@Test
	void watchCallNeverOpensAPaperTrade() {
		// The root of the old losses: ~80% of paper trades came from no-edge 50/50 reads.
		notFrozenClearWithSignals();
		Recommendation watch = recWithAction(RecommendationAction.WATCH);
		when(recommendations.create(anyString(), any(), any(), anyString(), anyString())).thenReturn(watch);

		trigger.trigger("AAPL");

		verify(investor, never()).open(any());
	}
}
