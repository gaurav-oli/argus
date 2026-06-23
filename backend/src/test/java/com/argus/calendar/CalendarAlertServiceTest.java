package com.argus.calendar;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.argus.agent.AgentEventPublisher;
import com.argus.notification.NotificationStream;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** Lead-time threshold + urgency logic for pre-event alerts (Story 5.2). */
class CalendarAlertServiceTest {

	private final CalendarEventRepository events = mock(CalendarEventRepository.class);
	private final CalendarEventAlertRepository alerts = mock(CalendarEventAlertRepository.class);
	private final AgentEventPublisher publisher = mock(AgentEventPublisher.class);
	private final CalendarAlertService service = new CalendarAlertService(events, alerts, publisher);

	private static final LocalDate TODAY = LocalDate.now(ZoneId.of("America/Toronto"));

	private void upcoming(CalendarEvent... e) {
		when(events.findByEventDateBetweenOrderByEventDateAsc(any(), any())).thenReturn(List.of(e));
		when(alerts.existsByEventIdAndLeadDays(any(), anyInt())).thenReturn(false);
	}

	private static CalendarEvent event(CalendarEventType type, String ticker, long daysOut) {
		return new CalendarEvent(type, ticker, "evt", TODAY.plusDays(daysOut), "src", "ext-" + type + daysOut);
	}

	private CalendarEventAlert firedAlert() {
		ArgumentCaptor<CalendarEventAlert> c = ArgumentCaptor.forClass(CalendarEventAlert.class);
		verify(alerts).save(c.capture());
		return c.getValue();
	}

	@Test
	void earningsThreeDaysOutFiresGreenThreeDayReminder() {
		upcoming(event(CalendarEventType.EARNINGS, "AAPL", 3));
		assertEquals(1, service.scan());
		verify(publisher).publish(eq(NotificationStream.KEY), eq("calendar.event_reminder"), any());
		assertEquals(3, firedAlert().getLeadDays());
		assertEquals(AlertUrgency.GREEN, firedAlert().getUrgency());
	}

	@Test
	void earningsOneDayOutFiresYellowOneDayReminder() {
		upcoming(event(CalendarEventType.EARNINGS, "AAPL", 1));
		assertEquals(1, service.scan());
		assertEquals(1, firedAlert().getLeadDays());
		assertEquals(AlertUrgency.YELLOW, firedAlert().getUrgency());
	}

	@Test
	void noReminderBeforeAnyLeadThreshold() {
		upcoming(event(CalendarEventType.EARNINGS, "AAPL", 5)); // earnings thresholds are 3 and 1
		assertEquals(0, service.scan());
		verify(alerts, never()).save(any());
		verify(publisher, never()).publish(anyString(), anyString(), any());
	}

	@Test
	void exDividendUsesFiveDayLead() {
		upcoming(event(CalendarEventType.EX_DIVIDEND, "MSFT", 5));
		assertEquals(1, service.scan());
		assertEquals(5, firedAlert().getLeadDays());
		assertEquals(AlertUrgency.GREEN, firedAlert().getUrgency());
	}

	@Test
	void macroEventUsesTwoDayLeadAndYellowWithin24h() {
		upcoming(event(CalendarEventType.FED, null, 1)); // FED lead = 2; 1 day out ⇒ within 24h
		assertEquals(1, service.scan());
		assertEquals(2, firedAlert().getLeadDays());
		assertEquals(AlertUrgency.YELLOW, firedAlert().getUrgency());
	}

	@Test
	void alreadyFiredThresholdIsNotRepeated() {
		when(events.findByEventDateBetweenOrderByEventDateAsc(any(), any()))
				.thenReturn(List.of(event(CalendarEventType.EARNINGS, "AAPL", 3)));
		when(alerts.existsByEventIdAndLeadDays(any(), eq(3))).thenReturn(true);

		assertEquals(0, service.scan());
		verify(alerts, never()).save(any());
		verify(publisher, never()).publish(anyString(), anyString(), any());
	}
}
