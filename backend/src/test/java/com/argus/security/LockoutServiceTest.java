package com.argus.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.argus.notification.Notification;
import com.argus.notification.NotificationService;
import com.argus.notification.UrgencyTier;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/** Secondary-device push alert on the alert-threshold lockout (FR-38, Epic 8 follow-up). */
class LockoutServiceTest {

	private final LockoutProperties props =
			new LockoutProperties(3, Duration.ofSeconds(30), 5, Duration.ofMinutes(10), 10, Duration.ofHours(1));
	private final NotificationService notifications = mock(NotificationService.class);

	@SuppressWarnings("unchecked")
	private LockoutService serviceWithFailCount(long fails) {
		StringRedisTemplate redis = mock(StringRedisTemplate.class);
		ValueOperations<String, String> values = mock(ValueOperations.class);
		when(redis.opsForValue()).thenReturn(values);
		when(values.increment(LockoutService.KEY_FAILS)).thenReturn(fails);
		return new LockoutService(redis, props, notifications);
	}

	@Test
	void crossingAlertThresholdPushesACriticalSecondaryDeviceAlert() {
		LockoutService service = serviceWithFailCount(5);

		assertThrows(LockedException.class, service::recordFailure);

		verify(notifications).notify(any(Notification.class));
	}

	@Test
	void alertIsCriticalAndCarriesNoTicker() {
		// Non-ticker (Notification.of) so dedup never applies — each real lockout must always reach
		// the user's other devices, and CRITICAL bypasses the fatigue gate/quiet hours.
		LockoutService service = serviceWithFailCount(5);

		assertThrows(LockedException.class, service::recordFailure);

		org.mockito.ArgumentCaptor<Notification> captor = org.mockito.ArgumentCaptor.forClass(Notification.class);
		verify(notifications).notify(captor.capture());
		Notification sent = captor.getValue();
		assertEquals(UrgencyTier.CRITICAL, sent.tier());
		assertNull(sent.ticker());
	}

	@Test
	void belowAlertThresholdDoesNotPush() {
		LockoutService service = serviceWithFailCount(3); // warnThreshold only

		assertThrows(LockedException.class, service::recordFailure);

		verify(notifications, never()).notify(any());
	}

	@Test
	void aFailingPushNeverMasksTheLockout() {
		// Best-effort: LockedException (the real signal to the caller) must still throw even if the
		// notification pipeline itself blows up.
		LockoutService service = serviceWithFailCount(5);
		when(notifications.notify(any())).thenThrow(new RuntimeException("push boom"));

		assertThrows(LockedException.class, service::recordFailure);
	}

	@Test
	void fullLockThresholdAlsoDoesNotDoubleAlert() {
		// Full lock has its own distinct handling (requires another device) — the alert-tier push is
		// specific to the alert threshold, not repeated at full lock.
		LockoutService service = serviceWithFailCount(10);

		assertThrows(LockedException.class, service::recordFailure);

		verify(notifications, never()).notify(any());
	}
}
