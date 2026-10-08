package com.argus.recommendation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.argus.notification.Notification;
import com.argus.notification.NotificationService;
import com.argus.notification.UrgencyTier;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Pure transition rules for Agent 5 graduation (Story 6.6, FR-11). */
class GraduationServiceTest {

	// evaluate(current, total, wins, rollingWins, rollingCount). rolling 50% (5/10) avoids freeze.
	private static GraduationState eval(GraduationState s, int total, int wins) {
		return GraduationService.evaluate(s, total, wins, 5, 10);
	}

	@Test
	void shadowPromotesToProbationAt20TradesAnd70Percent() {
		assertEquals(GraduationState.PROBATION, eval(GraduationState.SHADOW, 20, 14));
	}

	@Test
	void shadowStaysBelowWinRateOrTradeCount() {
		assertEquals(GraduationState.SHADOW, eval(GraduationState.SHADOW, 20, 13)); // 65%
		assertEquals(GraduationState.SHADOW, GraduationService.evaluate(GraduationState.SHADOW, 19, 19, 9, 9));
	}

	@Test
	void probationPromotesToActiveWithSustainedPerformance() {
		assertEquals(GraduationState.ACTIVE, eval(GraduationState.PROBATION, 40, 24)); // 60%
		assertEquals(GraduationState.PROBATION, eval(GraduationState.PROBATION, 40, 23)); // 57.5%
	}

	@Test
	void activeDemotesWhenRollingWinRateUnderFifty() {
		assertEquals(GraduationState.PROBATION,
				GraduationService.evaluate(GraduationState.ACTIVE, 50, 30, 4, 10)); // rolling 40%
	}

	@Test
	void activeStaysWhenRollingWinRateHealthy() {
		assertEquals(GraduationState.ACTIVE,
				GraduationService.evaluate(GraduationState.ACTIVE, 50, 30, 6, 10)); // rolling 60%
	}

	@Test
	void seriousFailurePatternFreezesFromAnyState() {
		assertEquals(GraduationState.FROZEN,
				GraduationService.evaluate(GraduationState.ACTIVE, 50, 20, 2, 10)); // rolling 20%
		assertEquals(GraduationState.FROZEN,
				GraduationService.evaluate(GraduationState.SHADOW, 15, 5, 2, 10));
	}

	@Test
	void rollingRulesRequireAFullTenTradeWindow() {
		// Only 8 trades: rolling rules (freeze/demote) don't apply yet.
		assertEquals(GraduationState.ACTIVE, GraduationService.evaluate(GraduationState.ACTIVE, 8, 1, 1, 8));
	}

	@Test
	void frozenIsTerminal() {
		assertEquals(GraduationState.FROZEN, GraduationService.evaluate(GraduationState.FROZEN, 100, 100, 10, 10));
	}

	@Test
	void badgesAndRecommendability() {
		assertEquals("UNPROVEN", GraduationState.PROBATION.badge());
		assertEquals("FROZEN", GraduationState.FROZEN.badge());
		assertEquals(true, GraduationState.ACTIVE.canRecommend());
		assertEquals(false, GraduationState.SHADOW.canRecommend());
		assertEquals(false, GraduationState.FROZEN.canRecommend());
	}

	// ---- resume() — manual review (instance method, needs mocked repositories) ----

	@Test
	void resumeMovesFrozenBackToShadowNotStraightToTrusted() {
		AgentGraduationRepository repo = mock(AgentGraduationRepository.class);
		AgentGraduation g = new AgentGraduation();
		g.setState(GraduationState.FROZEN);
		when(repo.findById(AgentGraduation.SINGLETON_ID)).thenReturn(Optional.of(g));
		GraduationService service = new GraduationService(repo, mock(PaperTradeRepository.class),
				mock(NotificationService.class));

		GraduationState result = service.resume();

		assertEquals(GraduationState.SHADOW, result);
		assertEquals(GraduationState.SHADOW, g.getState());
		verify(repo).save(g);
	}

	@Test
	void resumeIsANoOpWhenNotCurrentlyFrozen() {
		AgentGraduationRepository repo = mock(AgentGraduationRepository.class);
		AgentGraduation g = new AgentGraduation();
		g.setState(GraduationState.ACTIVE);
		when(repo.findById(AgentGraduation.SINGLETON_ID)).thenReturn(Optional.of(g));
		GraduationService service = new GraduationService(repo, mock(PaperTradeRepository.class),
				mock(NotificationService.class));

		GraduationState result = service.resume();

		assertEquals(GraduationState.ACTIVE, result, "resume() must not skip the earn-your-way-up ladder");
		verify(repo, never()).save(any());
	}

	// ---- recordOutcome() freeze alert — the 2026-09-02 incident this session fixed ----

	@Test
	void recordOutcomePushesACriticalAlertOnFreeze() {
		AgentGraduationRepository repo = mock(AgentGraduationRepository.class);
		PaperTradeRepository trades = mock(PaperTradeRepository.class);
		NotificationService notifications = mock(NotificationService.class);
		AgentGraduation g = new AgentGraduation();
		g.setState(GraduationState.ACTIVE);
		when(repo.findById(AgentGraduation.SINGLETON_ID)).thenReturn(Optional.of(g));
		when(trades.countByCountsForGraduationTrue()).thenReturn(50L);
		when(trades.countByWonTrueAndCountsForGraduationTrue()).thenReturn(20L);
		// Rolling-10 window with 2 wins (20%) — below the 30% freeze threshold.
		List<PaperTrade> rolling = List.of(
				won(), won(), lost(), lost(), lost(), lost(), lost(), lost(), lost(), lost());
		when(trades.findTop10ByCountsForGraduationTrueOrderByIdDesc()).thenReturn(rolling);
		GraduationService service = new GraduationService(repo, trades, notifications);

		GraduationState result = service.recordOutcome(false, 999L);

		assertEquals(GraduationState.FROZEN, result);
		verify(notifications).notify(any(Notification.class));
	}

	@Test
	void recordOutcomeAlertIsCriticalAndNonTicker() {
		AgentGraduationRepository repo = mock(AgentGraduationRepository.class);
		PaperTradeRepository trades = mock(PaperTradeRepository.class);
		NotificationService notifications = mock(NotificationService.class);
		AgentGraduation g = new AgentGraduation();
		g.setState(GraduationState.ACTIVE);
		when(repo.findById(AgentGraduation.SINGLETON_ID)).thenReturn(Optional.of(g));
		when(trades.countByCountsForGraduationTrue()).thenReturn(50L);
		when(trades.countByWonTrueAndCountsForGraduationTrue()).thenReturn(20L);
		when(trades.findTop10ByCountsForGraduationTrueOrderByIdDesc()).thenReturn(List.of(
				won(), won(), lost(), lost(), lost(), lost(), lost(), lost(), lost(), lost()));
		GraduationService service = new GraduationService(repo, trades, notifications);

		service.recordOutcome(false, 999L);

		org.mockito.ArgumentCaptor<Notification> captor = org.mockito.ArgumentCaptor.forClass(Notification.class);
		verify(notifications).notify(captor.capture());
		assertEquals(UrgencyTier.CRITICAL, captor.getValue().tier());
		assertEquals(null, captor.getValue().ticker());
	}

	@Test
	void recordOutcomeDoesNotAlertWhenNotFreezing() {
		AgentGraduationRepository repo = mock(AgentGraduationRepository.class);
		PaperTradeRepository trades = mock(PaperTradeRepository.class);
		NotificationService notifications = mock(NotificationService.class);
		AgentGraduation g = new AgentGraduation();
		g.setState(GraduationState.ACTIVE);
		when(repo.findById(AgentGraduation.SINGLETON_ID)).thenReturn(Optional.of(g));
		when(trades.countByCountsForGraduationTrue()).thenReturn(50L);
		when(trades.countByWonTrueAndCountsForGraduationTrue()).thenReturn(30L);
		// Healthy rolling window (60%) — no state change at all.
		when(trades.findTop10ByCountsForGraduationTrueOrderByIdDesc()).thenReturn(List.of(
				won(), won(), won(), won(), won(), won(), lost(), lost(), lost(), lost()));
		GraduationService service = new GraduationService(repo, trades, notifications);

		GraduationState result = service.recordOutcome(true, 999L);

		assertEquals(GraduationState.ACTIVE, result);
		verify(notifications, never()).notify(any());
	}

	@Test
	void recordOutcomeAFailingAlertNeverMasksTheFreezeItself() {
		AgentGraduationRepository repo = mock(AgentGraduationRepository.class);
		PaperTradeRepository trades = mock(PaperTradeRepository.class);
		NotificationService notifications = mock(NotificationService.class);
		AgentGraduation g = new AgentGraduation();
		g.setState(GraduationState.ACTIVE);
		when(repo.findById(AgentGraduation.SINGLETON_ID)).thenReturn(Optional.of(g));
		when(trades.countByCountsForGraduationTrue()).thenReturn(50L);
		when(trades.countByWonTrueAndCountsForGraduationTrue()).thenReturn(20L);
		when(trades.findTop10ByCountsForGraduationTrueOrderByIdDesc()).thenReturn(List.of(
				won(), won(), lost(), lost(), lost(), lost(), lost(), lost(), lost(), lost()));
		when(notifications.notify(any())).thenThrow(new RuntimeException("push boom"));
		GraduationService service = new GraduationService(repo, trades, notifications);

		GraduationState result = service.recordOutcome(false, 999L);

		assertEquals(GraduationState.FROZEN, result, "the freeze itself must still take effect");
		assertEquals(GraduationState.FROZEN, g.getState());
	}

	private static PaperTrade won() {
		return new PaperTrade(true, 1L);
	}

	private static PaperTrade lost() {
		return new PaperTrade(false, 1L);
	}
}
