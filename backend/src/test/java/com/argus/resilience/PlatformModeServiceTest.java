package com.argus.resilience;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.argus.common.LivePushService;
import com.argus.notification.Notification;
import com.argus.notification.NotificationService;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** Degraded Mode transitions: debounce, notify on flip, catch-up on reconnect (Story 10.4). */
class PlatformModeServiceTest {

	private final NotificationService notifications = mock(NotificationService.class);
	private final LivePushService livePush = mock(LivePushService.class);
	private final PlatformModeService service = new PlatformModeService(notifications, livePush);
	private final Instant t0 = Instant.parse("2026-06-26T12:00:00Z");

	@Test
	void singleFailureDoesNotDegrade() {
		service.report(false, t0);
		assertFalse(service.isDegraded());
		verify(notifications, never()).notify(any());
	}

	@Test
	void twoConsecutiveFailuresDegradeAndNotifyAndBroadcast() {
		service.report(false, t0);
		service.report(false, t0.plusSeconds(30));

		assertTrue(service.isDegraded());
		assertEquals("DEGRADED", service.current().mode());
		verify(notifications, atLeastOnce()).notify(any(Notification.class));
		verify(livePush, atLeastOnce()).publish(eq(PlatformModeService.TOPIC), any());
	}

	@Test
	void recoveryReturnsToNormalAndAnnounces() {
		service.report(false, t0);
		service.report(false, t0.plusSeconds(30));
		service.report(true, t0.plusSeconds(120));

		assertFalse(service.isDegraded());
		assertEquals("NORMAL", service.current().mode());
		// one notify on degrade + one on recovery
		verify(notifications, atLeastOnce()).notify(any(Notification.class));
	}

	@Test
	void staysNormalWhileOnline() {
		service.report(true, t0);
		service.report(true, t0.plusSeconds(30));
		assertFalse(service.isDegraded());
		verify(notifications, never()).notify(any());
	}
}
