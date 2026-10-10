package com.argus.notification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
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
		int carried = service.send();

		assertEquals(2, carried);
		verify(push).sendToAll(eq("Your weekly digest"), contains("Alpha"), eq("/intelligence"), eq(false), any());
		items.forEach(i -> assertNotNull(i.getDeliveredAt()));
		verify(deferred).saveAll(items);
	}

	@Test
	void emptyWeekSkipsThePush() {
		when(deferred.findByChannelAndDeliveredAtIsNullAndCreatedAtAfterOrderByCreatedAtDesc(
				eq(Channel.DIGEST), any())).thenReturn(List.of());

		assertEquals(0, service.send());
		verify(push, never()).sendToAll(anyString(), anyString(), anyString(), anyBoolean(), any());
	}

	@Test
	void prefsGateSuppressesThePushButStillMarksDelivered() {
		// The user turned briefing-class pushes off: no push, but items shouldn't pile up forever.
		List<DeferredNotification> items = List.of(item("Alpha"));
		when(deferred.findByChannelAndDeliveredAtIsNullAndCreatedAtAfterOrderByCreatedAtDesc(
				eq(Channel.DIGEST), any())).thenReturn(items);
		// S-C1: the push is filtered per device owner — someone with briefings off is skipped.
		when(prefs.allowFor(7L, NotificationPreferencesService.Category.BRIEFING, null, false)).thenReturn(false);
		when(prefs.allowFor(8L, NotificationPreferencesService.Category.BRIEFING, null, false)).thenReturn(true);

		service.send();

		@SuppressWarnings("unchecked")
		org.mockito.ArgumentCaptor<java.util.function.Predicate<Long>> who = org.mockito.ArgumentCaptor.forClass(java.util.function.Predicate.class);
		verify(push).sendToAll(anyString(), anyString(), anyString(), anyBoolean(), who.capture());
		org.junit.jupiter.api.Assertions.assertFalse(who.getValue().test(7L));
		org.junit.jupiter.api.Assertions.assertTrue(who.getValue().test(8L));
		items.forEach(i -> assertNotNull(i.getDeliveredAt()));
	}
}
