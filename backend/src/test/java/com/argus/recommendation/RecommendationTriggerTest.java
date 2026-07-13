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
	private final RecommendationTrigger trigger = new RecommendationTrigger(
			gatherer, recommendations, graduation, quietPeriod, universe, investor);

	private final AgentSignal aSignal = new AgentSignal("agent-1-news", SignalDirection.BULLISH, 1, "x");

	private void notFrozenClearWithSignals() {
		lenient().when(graduation.currentState()).thenReturn(GraduationState.ACTIVE);
		lenient().when(quietPeriod.statusFor(anyString())).thenReturn(QuietPeriodStatus.clear());
		lenient().when(gatherer.gather(anyString())).thenReturn(List.of(aSignal));
		lenient().when(recommendations.create(anyString(), any(), any(), anyString()))
				.thenReturn(mock(Recommendation.class));
	}

	@Test
	void producesRecommendationWhenGatesAllow() {
		notFrozenClearWithSignals();
		trigger.trigger("AAPL");
		verify(recommendations).create(anyString(), any(), any(), anyString());
	}

	@Test
	void frozenSuppressesAllRecommendations() {
		when(graduation.currentState()).thenReturn(GraduationState.FROZEN);
		assertTrue(trigger.trigger("AAPL").isEmpty());
		verify(recommendations, never()).create(anyString(), any(), any(), anyString());
	}

	@Test
	void quietPeriodSuppressesTheProbabilityCard() {
		when(graduation.currentState()).thenReturn(GraduationState.ACTIVE);
		when(quietPeriod.statusFor("AAPL"))
				.thenReturn(new QuietPeriodStatus(QuietPeriodStatus.Status.QUIET, LocalDate.now(), 1));
		assertTrue(trigger.trigger("AAPL").isEmpty());
		verify(recommendations, never()).create(anyString(), any(), any(), anyString());
	}

	@Test
	void noSignalsProducesNothing() {
		when(graduation.currentState()).thenReturn(GraduationState.ACTIVE);
		when(quietPeriod.statusFor(anyString())).thenReturn(QuietPeriodStatus.clear());
		when(gatherer.gather("AAPL")).thenReturn(List.of());
		assertTrue(trigger.trigger("AAPL").isEmpty());
		verify(recommendations, never()).create(anyString(), any(), any(), anyString());
	}
}
