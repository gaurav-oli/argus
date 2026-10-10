package com.argus.notification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.argus.portfolio.PositionRepository;
import com.argus.push.PushService;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The alert-discipline pipeline: dedup (8.4) → fatigue gate (8.3) → tier routing (8.2). */
class NotificationServiceTest {

	private final NotificationDedupStore dedup = mock(NotificationDedupStore.class);
	private final PushService push = mock(PushService.class);
	private final NotificationPreferencesService prefs = mock(NotificationPreferencesService.class);
	private final DeferredNotificationRepository deferred = mock(DeferredNotificationRepository.class);
	private final PositionRepository positions = mock(PositionRepository.class);
	private final NotificationProperties props = new NotificationProperties(0.60, 0.02, 1800);
	private final NotificationService service =
			new NotificationService(props, dedup, push, prefs, deferred, positions);

	@BeforeEach
	void passDedupByDefault() {
		// Mockito's default for boolean is false (= deduped); make alerts pass dedup unless a test says otherwise.
		when(dedup.accept(any(), any(), anyDouble(), any())).thenReturn(true);
		// Preferences allow everything by default (these tests predate prefs and assert routing behaviour).
		when(prefs.allow(any(), any(), anyBoolean())).thenReturn(true);
		when(prefs.allowFor(any(), any(), any(), anyBoolean())).thenReturn(true);
		when(positions.userIdsHoldingTicker(anyString())).thenReturn(List.of(42L));
	}

	@Test
	void criticalTickerPushGoesOnlyToHoldersNotBroadcast() {
		// confidence/impact are zero — below the gate — but CRITICAL must still fire.
		NotificationOutcome out = service.notify(Notification.forTicker(UrgencyTier.CRITICAL, "ABCD", "STRANGER",
				0.0, 0.0, "danger", "body", "/intelligence"));

		assertEquals(NotificationOutcome.PUSHED, out);
		verify(push).sendToUser(eq(42L), eq("danger"), eq("body"), eq("/intelligence"), eq(true));
		verify(push, never()).sendToAll(anyString(), anyString(), anyString(), anyBoolean(), any());
	}

	@Test
	void importantTickerPushGoesOnlyToHolders() {
		NotificationOutcome out = service.notify(Notification.forTicker(UrgencyTier.IMPORTANT, "AAPL", "BULLISH",
				0.80, 0.10, "buy", "body", "/recs"));

		assertEquals(NotificationOutcome.PUSHED, out);
		verify(push).sendToUser(eq(42L), eq("buy"), eq("body"), eq("/recs"), eq(false));
		verify(push, never()).sendToAll(anyString(), anyString(), anyString(), anyBoolean(), any());
	}

	@Test
	void platformWideAlertWithoutTickerStillBroadcasts() {
		NotificationOutcome out = service.notify(Notification.of(UrgencyTier.CRITICAL, "platform", "body", "/ops"));

		assertEquals(NotificationOutcome.PUSHED, out);
		verify(push).sendToAll(eq("platform"), eq("body"), eq("/ops"), eq(true), any());
		verify(push, never()).sendToUser(any(), anyString(), anyString(), anyString(), anyBoolean());
	}

	@Test
	void eachHoldersOwnPreferencesDecide() {
		when(positions.userIdsHoldingTicker("AAPL")).thenReturn(List.of(42L, 43L));
		when(prefs.allowFor(eq(43L), any(), any(), anyBoolean())).thenReturn(false); // 43 muted AAPL

		NotificationOutcome out = service.notify(Notification.forTicker(UrgencyTier.IMPORTANT, "AAPL", "BULLISH",
				0.80, 0.10, "buy", "body", "/recs"));

		assertEquals(NotificationOutcome.PUSHED, out);
		verify(push).sendToUser(eq(42L), eq("buy"), eq("body"), eq("/recs"), eq(false));
		verify(push, never()).sendToUser(eq(43L), anyString(), anyString(), anyString(), anyBoolean());
	}

	@Test
	void suppressedWhenEveryHolderOptedOut() {
		when(prefs.allowFor(any(), any(), any(), anyBoolean())).thenReturn(false);

		NotificationOutcome out = service.notify(Notification.forTicker(UrgencyTier.IMPORTANT, "AAPL", "BULLISH",
				0.80, 0.10, "buy", "body", "/recs"));

		assertEquals(NotificationOutcome.SUPPRESSED_PREFS, out);
		verify(push, never()).sendToUser(any(), anyString(), anyString(), anyString(), anyBoolean());
	}

	@Test
	void tickerWithNoHoldersDoesNotBroadcast() {
		when(positions.userIdsHoldingTicker("ABCD")).thenReturn(List.of());

		NotificationOutcome out = service.notify(Notification.forTicker(UrgencyTier.CRITICAL, "ABCD", "STRANGER",
				0.99, 0.50, "danger", "body", "/intelligence"));

		assertEquals(NotificationOutcome.PUSHED, out);
		verify(push, never()).sendToAll(anyString(), anyString(), anyString(), anyBoolean(), any());
		verify(push, never()).sendToUser(any(), anyString(), anyString(), anyString(), anyBoolean());
	}

	@Test
	void gateSuppressesLowConfidenceNonCritical() {
		NotificationOutcome out = service.notify(Notification.forTicker(UrgencyTier.IMPORTANT, "AAPL", "BULLISH",
				0.40, 0.10, "buy", "body", "/recs"));

		assertEquals(NotificationOutcome.SUPPRESSED_GATE, out);
		verify(push, never()).sendToAll(anyString(), anyString(), anyString(), anyBoolean(), any());
		verify(push, never()).sendToUser(any(), anyString(), anyString(), anyString(), anyBoolean());
	}

	@Test
	void gateSuppressesLowPortfolioImpactNonCritical() {
		NotificationOutcome out = service.notify(Notification.forTicker(UrgencyTier.IMPORTANT, "AAPL", "BULLISH",
				0.90, 0.001, "buy", "body", "/recs"));

		assertEquals(NotificationOutcome.SUPPRESSED_GATE, out);
		verify(push, never()).sendToAll(anyString(), anyString(), anyString(), anyBoolean(), any());
		verify(push, never()).sendToUser(any(), anyString(), anyString(), anyString(), anyBoolean());
	}

	@Test
	void normalDefersToBriefingPersistedNoPush() {
		NotificationOutcome out = service.notify(Notification.forTicker(UrgencyTier.NORMAL, "AAPL", "BULLISH",
				0.90, 0.20, "fyi", "body", "/recs"));

		assertEquals(NotificationOutcome.DEFERRED_BRIEFING, out);
		verify(push, never()).sendToAll(anyString(), anyString(), anyString(), anyBoolean(), any());
		// The follow-up: the deferred item is now actually persisted for the next briefing to carry.
		org.mockito.ArgumentCaptor<DeferredNotification> saved =
				org.mockito.ArgumentCaptor.forClass(DeferredNotification.class);
		verify(deferred).save(saved.capture());
		assertEquals(DeferredNotification.Channel.BRIEFING, saved.getValue().getChannel());
		assertEquals("fyi", saved.getValue().getTitle());
	}

	@Test
	void infoDefersToDigestPersistedNoPush() {
		NotificationOutcome out = service.notify(Notification.forTicker(UrgencyTier.INFO, "AAPL", "BULLISH",
				0.90, 0.20, "fyi", "body", "/recs"));

		assertEquals(NotificationOutcome.DEFERRED_DIGEST, out);
		verify(push, never()).sendToAll(anyString(), anyString(), anyString(), anyBoolean(), any());
		org.mockito.ArgumentCaptor<DeferredNotification> saved =
				org.mockito.ArgumentCaptor.forClass(DeferredNotification.class);
		verify(deferred).save(saved.capture());
		assertEquals(DeferredNotification.Channel.DIGEST, saved.getValue().getChannel());
	}

	@Test
	void dedupSuppressesBeforeAnyPush() {
		when(dedup.accept(eq("AAPL"), eq("BULLISH"), anyDouble(), any(Duration.class))).thenReturn(false);

		NotificationOutcome out = service.notify(Notification.forTicker(UrgencyTier.CRITICAL, "AAPL", "BULLISH",
				0.99, 0.50, "dup", "body", "/recs"));

		assertEquals(NotificationOutcome.SUPPRESSED_DEDUP, out);
		verify(push, never()).sendToAll(anyString(), anyString(), anyString(), anyBoolean(), any());
		verify(push, never()).sendToUser(any(), anyString(), anyString(), anyString(), anyBoolean());
	}
}
