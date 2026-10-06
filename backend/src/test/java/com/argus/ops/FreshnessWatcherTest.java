package com.argus.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.argus.notification.Notification;
import com.argus.notification.NotificationService;
import com.argus.notification.UrgencyTier;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** One alert when a source goes stale, none while it stays stale, one note when it recovers. */
class FreshnessWatcherTest {

	private final FreshnessService freshness = mock(FreshnessService.class);
	private final NotificationService notifications = mock(NotificationService.class);
	private final FreshnessWatcher watcher = new FreshnessWatcher(freshness, notifications);

	private static FreshnessView view(boolean fundamentalsStale) {
		return new FreshnessView(List.of(
				new SourceFreshness("news", "News (Agent 1)", Instant.now(), 5L, false, 30),
				new SourceFreshness("fundamentals", "Fundamentals, oldest ticker (Agent 12)", Instant.now(),
						fundamentalsStale ? 4_320L : 60L, fundamentalsStale, 2_160)), fundamentalsStale);
	}

	@Test
	void alertsOnceWhileStaleThenNotesTheRecovery() {
		when(freshness.snapshot()).thenReturn(view(true));
		watcher.check();
		watcher.check(); // still stale: no second alert

		when(freshness.snapshot()).thenReturn(view(false));
		watcher.check();

		ArgumentCaptor<Notification> sent = ArgumentCaptor.forClass(Notification.class);
		verify(notifications, times(2)).notify(sent.capture());
		assertEquals(UrgencyTier.IMPORTANT, sent.getAllValues().get(0).tier());
		assertEquals("Fundamentals, oldest ticker (Agent 12) is stale", sent.getAllValues().get(0).title());
		assertEquals(UrgencyTier.INFO, sent.getAllValues().get(1).tier());
	}

	@Test
	void agesReadLikeAPerson() {
		assertEquals("45 min", FreshnessWatcher.humanAge(45L));
		assertEquals("36h", FreshnessWatcher.humanAge(2_160L));
		assertEquals("3 days", FreshnessWatcher.humanAge(4_320L));
	}
}
