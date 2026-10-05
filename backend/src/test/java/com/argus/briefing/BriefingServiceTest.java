package com.argus.briefing;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.argus.calendar.CalendarEventRepository;
import com.argus.intelligence.NewsArticleRepository;
import com.argus.model.ModelGateway;
import com.argus.notification.DeferredNotificationRepository;
import com.argus.notification.NotificationPreferencesService;
import com.argus.portfolio.HealthScoreResult;
import com.argus.portfolio.HealthScoreService;
import com.argus.portfolio.LivePortfolioService;
import com.argus.portfolio.PortfolioSnapshot;
import com.argus.push.PushService;
import com.argus.recommendation.RecommendationService;
import com.argus.security.AppUser;
import com.argus.security.AppUserRepository;
import com.argus.security.CurrentUserContext;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Phase 2 (multi-user): {@link BriefingService} must generate each person's OWN briefing, greet them
 * by name, and push it only to their own device — never broadcast it like a shared alert.
 */
class BriefingServiceTest {

	private final LivePortfolioService livePortfolio = mock(LivePortfolioService.class);
	private final HealthScoreService healthScore = mock(HealthScoreService.class);
	private final NewsArticleRepository news = mock(NewsArticleRepository.class);
	private final RecommendationService recommendations = mock(RecommendationService.class);
	private final CalendarEventRepository calendar = mock(CalendarEventRepository.class);
	private final ModelGateway gateway = mock(ModelGateway.class);
	private final PushService push = mock(PushService.class);
	private final NotificationPreferencesService prefs = mock(NotificationPreferencesService.class);
	private final DeferredNotificationRepository deferred = mock(DeferredNotificationRepository.class);
	private final BriefingRepository briefings = mock(BriefingRepository.class);
	private final AppUserRepository users = mock(AppUserRepository.class);

	private final BriefingService service = new BriefingService(livePortfolio, healthScore, news, recommendations,
			calendar, gateway, push, prefs, deferred, briefings, users, 16);

	private void stubFacts() {
		PortfolioSnapshot snapshot = new PortfolioSnapshot(new BigDecimal("1000"), new BigDecimal("900"),
				new BigDecimal("100"), new BigDecimal("740"), false, Instant.now(), List.of());
		when(livePortfolio.currentSnapshot()).thenReturn(snapshot);
		when(healthScore.compute()).thenReturn(new HealthScoreResult(80, List.of(), Instant.now()));
		when(news.countByPublishedAtAfter(any())).thenReturn(0L);
		when(recommendations.recent()).thenReturn(List.of());
		when(calendar.findByEventDateBetweenOrderByEventDateAsc(any(), any())).thenReturn(List.of());
		when(deferred.findByChannelAndDeliveredAtIsNullOrderByCreatedAtDesc(any())).thenReturn(List.of());
		when(briefings.save(any())).thenAnswer(inv -> inv.getArgument(0));
		when(prefs.allow(NotificationPreferencesService.Category.BRIEFING)).thenReturn(true);
	}

	@Test
	void fallbackBriefingGreetsTheSignedInPersonByFirstName() {
		stubFacts();
		when(gateway.generate(anyString())).thenThrow(new RuntimeException("model unavailable"));
		AppUser alice = new AppUser("sub-alice", "alice@example.com", "Alice Smith", null, false);
		when(users.findById(42L)).thenReturn(Optional.of(alice));

		Briefing result = CurrentUserContext.callAs(42L, service::generate);

		assertTrue(result.getBody().contains("Alice"), "fallback text should greet the person by first name");
	}

	@Test
	void personalizedPushGoesOnlyToThatPersonsOwnDeviceNeverBroadcast() {
		stubFacts();
		when(gateway.generate(anyString())).thenThrow(new RuntimeException("model unavailable"));
		AppUser bob = new AppUser("sub-bob", "bob@example.com", "Bob Jones", null, false);
		when(users.findById(7L)).thenReturn(Optional.of(bob));

		CurrentUserContext.callAs(7L, service::generate);

		verify(push).sendToUser(eq(7L), anyString(), anyString(), anyString());
		verify(push, never()).sendToAll(anyString(), anyString(), anyString());
	}

	@Test
	void oneInvitedPersonsBriefingFailingDoesNotStopTheOthers() {
		stubFacts();
		when(gateway.generate(anyString())).thenThrow(new RuntimeException("model unavailable"));
		AppUser alice = new AppUser("sub-alice", "alice@example.com", "Alice Smith", null, false);
		AppUser bob = new AppUser("sub-bob", "bob@example.com", "Bob Jones", null, false);
		setId(alice, 1L);
		setId(bob, 2L);
		when(users.findAll()).thenReturn(List.of(alice, bob));
		when(users.findById(1L)).thenReturn(Optional.of(alice));
		when(users.findById(2L)).thenThrow(new RuntimeException("DB hiccup for bob")); // bob's run blows up

		service.scheduledBriefing();

		verify(briefings, times(1)).save(any()); // alice still got hers despite bob's failure
		verify(push).sendToUser(eq(1L), anyString(), anyString(), anyString());
		verify(push, never()).sendToUser(eq(2L), anyString(), anyString(), anyString());
	}

	/** Reflection-free id stamping isn't available on this hand-rolled entity outside JPA — set it via
	 * the package's own test-construction pattern instead of hiding a real setter in production code. */
	private static void setId(AppUser user, Long id) {
		try {
			var field = AppUser.class.getDeclaredField("id");
			field.setAccessible(true);
			field.set(user, id);
		} catch (ReflectiveOperationException ex) {
			throw new IllegalStateException(ex);
		}
	}
}
