package com.argus.notification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.argus.notification.DeferredNotification.Channel;
import com.argus.push.PushService;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The weekly digest (Story 8.2 follow-up): summarize + push + mark delivered; silent when empty. */
class DigestServiceTest {

	private final DeferredNotificationRepository deferred = mock(DeferredNotificationRepository.class);
	private final PushService push = mock(PushService.class);
	private final NotificationPreferencesService prefs = mock(NotificationPreferencesService.class);
	private final DigestService service = new DigestService(deferred, push, prefs);

	private static DeferredNotification item(String title) {
		return new DeferredNotification("INFO", title, "body", "/x", "AAPL", Channel.DIGEST);
	}

	@Test
	void pushesSummaryAndMarksDelivered() {
		List<DeferredNotification> items = List.of(item("Alpha"), item("Beta"));
		when(deferred.findByChannelAndDeliveredAtIsNullAndCreatedAtAfterOrderByCreatedAtDesc(
				eq(Channel.DIGEST), any())).thenReturn(items);
		when(prefs.allow(NotificationPreferencesService.Category.BRIEFING)).thenReturn(true);

		int carried = service.send();

		assertEquals(2, carried);
		verify(push).sendToAll(eq("Your weekly digest"), contains("Alpha"), eq("/intelligence"));
		items.forEach(i -> assertNotNull(i.getDeliveredAt()));
		verify(deferred).saveAll(items);
	}

	@Test
	void emptyWeekSkipsThePush() {
		when(deferred.findByChannelAndDeliveredAtIsNullAndCreatedAtAfterOrderByCreatedAtDesc(
				eq(Channel.DIGEST), any())).thenReturn(List.of());

		assertEquals(0, service.send());
		verify(push, never()).sendToAll(anyString(), anyString(), anyString());
	}

	@Test
	void prefsGateSuppressesThePushButStillMarksDelivered() {
		// The user turned briefing-class pushes off: no push, but items shouldn't pile up forever.
		List<DeferredNotification> items = List.of(item("Alpha"));
		when(deferred.findByChannelAndDeliveredAtIsNullAndCreatedAtAfterOrderByCreatedAtDesc(
				eq(Channel.DIGEST), any())).thenReturn(items);
		when(prefs.allow(NotificationPreferencesService.Category.BRIEFING)).thenReturn(false);

		service.send();

		verify(push, never()).sendToAll(anyString(), anyString(), anyString());
		items.forEach(i -> assertNotNull(i.getDeliveredAt()));
	}
}
