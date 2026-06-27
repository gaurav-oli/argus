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

import com.argus.push.PushService;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The alert-discipline pipeline: dedup (8.4) → fatigue gate (8.3) → tier routing (8.2). */
class NotificationServiceTest {

	private final NotificationDedupStore dedup = mock(NotificationDedupStore.class);
	private final PushService push = mock(PushService.class);
	private final NotificationProperties props = new NotificationProperties(0.60, 0.02, 1800);
	private final NotificationService service = new NotificationService(props, dedup, push);

	@BeforeEach
	void passDedupByDefault() {
		// Mockito's default for boolean is false (= deduped); make alerts pass dedup unless a test says otherwise.
		when(dedup.accept(any(), any(), anyDouble(), any())).thenReturn(true);
	}

	@Test
	void criticalPushesWithRequireAckAndBypassesGate() {
		// confidence/impact are zero — below the gate — but CRITICAL must still fire.
		NotificationOutcome out = service.notify(Notification.forTicker(UrgencyTier.CRITICAL, "ABCD", "STRANGER",
				0.0, 0.0, "danger", "body", "/intelligence"));

		assertEquals(NotificationOutcome.PUSHED, out);
		verify(push).sendToAll("danger", "body", "/intelligence", true);
	}

	@Test
	void importantAbovethresholdsPushesWithoutRequireAck() {
		NotificationOutcome out = service.notify(Notification.forTicker(UrgencyTier.IMPORTANT, "AAPL", "BULLISH",
				0.80, 0.10, "buy", "body", "/recs"));

		assertEquals(NotificationOutcome.PUSHED, out);
		verify(push).sendToAll("buy", "body", "/recs", false);
	}

	@Test
	void gateSuppressesLowConfidenceNonCritical() {
		NotificationOutcome out = service.notify(Notification.forTicker(UrgencyTier.IMPORTANT, "AAPL", "BULLISH",
				0.40, 0.10, "buy", "body", "/recs"));

		assertEquals(NotificationOutcome.SUPPRESSED_GATE, out);
		verify(push, never()).sendToAll(anyString(), anyString(), anyString(), anyBoolean());
	}

	@Test
	void gateSuppressesLowPortfolioImpactNonCritical() {
		NotificationOutcome out = service.notify(Notification.forTicker(UrgencyTier.IMPORTANT, "AAPL", "BULLISH",
				0.90, 0.001, "buy", "body", "/recs"));

		assertEquals(NotificationOutcome.SUPPRESSED_GATE, out);
		verify(push, never()).sendToAll(anyString(), anyString(), anyString(), anyBoolean());
	}

	@Test
	void normalDefersToBriefingNoPush() {
		NotificationOutcome out = service.notify(Notification.forTicker(UrgencyTier.NORMAL, "AAPL", "BULLISH",
				0.90, 0.20, "fyi", "body", "/recs"));

		assertEquals(NotificationOutcome.DEFERRED_BRIEFING, out);
		verify(push, never()).sendToAll(anyString(), anyString(), anyString(), anyBoolean());
	}

	@Test
	void infoDefersToDigestNoPush() {
		NotificationOutcome out = service.notify(Notification.forTicker(UrgencyTier.INFO, "AAPL", "BULLISH",
				0.90, 0.20, "fyi", "body", "/recs"));

		assertEquals(NotificationOutcome.DEFERRED_DIGEST, out);
		verify(push, never()).sendToAll(anyString(), anyString(), anyString(), anyBoolean());
	}

	@Test
	void dedupSuppressesBeforeAnyPush() {
		when(dedup.accept(eq("AAPL"), eq("BULLISH"), anyDouble(), any(Duration.class))).thenReturn(false);

		NotificationOutcome out = service.notify(Notification.forTicker(UrgencyTier.CRITICAL, "AAPL", "BULLISH",
				0.99, 0.50, "dup", "body", "/recs"));

		assertEquals(NotificationOutcome.SUPPRESSED_DEDUP, out);
		verify(push, never()).sendToAll(anyString(), anyString(), anyString(), anyBoolean());
	}
}
