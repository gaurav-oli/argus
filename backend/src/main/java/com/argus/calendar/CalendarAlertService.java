package com.argus.calendar;

import com.argus.agent.AgentEventPublisher;
import com.argus.notification.NotificationStream;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Fires lead-time reminders for upcoming calendar events (Story 5.2, FR-22). On a schedule it finds
 * events whose lead time has been reached — earnings 3d/1d, Fed/CPI/jobs/GDP 2d, ex-dividend 5d,
 * lock-up 7d — and queues exactly one reminder per threshold (GREEN normally, YELLOW within ~24h) on
 * the notifications stream for the briefing/notification service (Epic 8). The fired-alert table
 * keeps it idempotent across scans.
 */
@Service
public class CalendarAlertService {

	static final String EVENT_REMINDER = "calendar.event_reminder";
	private static final ZoneId TORONTO = ZoneId.of("America/Toronto");

	// Lead-day thresholds per event type, ascending (tightest first). FR-22.
	private static final Map<CalendarEventType, int[]> LEAD_DAYS = Map.of(
			CalendarEventType.EARNINGS, new int[] {1, 3},
			CalendarEventType.FED, new int[] {2},
			CalendarEventType.CPI, new int[] {2},
			CalendarEventType.JOBS, new int[] {2},
			CalendarEventType.GDP, new int[] {2},
			CalendarEventType.EX_DIVIDEND, new int[] {5},
			CalendarEventType.LOCKUP, new int[] {7});
	private static final int MAX_LEAD = 7;

	private static final Logger log = LoggerFactory.getLogger(CalendarAlertService.class);

	private final CalendarEventRepository events;
	private final CalendarEventAlertRepository alerts;
	private final AgentEventPublisher publisher;

	public CalendarAlertService(CalendarEventRepository events, CalendarEventAlertRepository alerts,
			AgentEventPublisher publisher) {
		this.events = events;
		this.alerts = alerts;
		this.publisher = publisher;
	}

	/** Hourly lead-time scan (the YELLOW/within-24h transition wants sub-daily granularity). */
	@Scheduled(cron = "0 0 * * * *")
	public void scheduledScan() {
		try {
			scan();
		} catch (RuntimeException ex) {
			log.warn("Calendar alert scan failed: {}", ex.getMessage());
		}
	}

	/** Run one scan. Returns the number of reminders queued this pass. */
	public int scan() {
		LocalDate today = LocalDate.now(TORONTO);
		int fired = 0;
		for (CalendarEvent event : events.findByEventDateBetweenOrderByEventDateAsc(today, today.plusDays(MAX_LEAD))) {
			long daysUntil = ChronoUnit.DAYS.between(today, event.getEventDate());
			if (daysUntil < 0) {
				continue;
			}
			Integer leadDays = tightestCrossedThreshold(event.getType(), daysUntil);
			if (leadDays == null || alerts.existsByEventIdAndLeadDays(event.getId(), leadDays)) {
				continue;
			}
			AlertUrgency urgency = daysUntil <= 1 ? AlertUrgency.YELLOW : AlertUrgency.GREEN;
			queue(event, daysUntil, leadDays, urgency);
			alerts.save(new CalendarEventAlert(event.getId(), leadDays, urgency));
			fired++;
		}
		if (fired > 0) {
			log.info("Calendar alerts: queued {} reminder(s)", fired);
		}
		return fired;
	}

	/** The smallest configured lead threshold the event has reached (null if none reached yet). */
	private static Integer tightestCrossedThreshold(CalendarEventType type, long daysUntil) {
		int[] thresholds = LEAD_DAYS.getOrDefault(type, new int[0]);
		for (int t : thresholds) { // ascending: first match is the tightest crossed
			if (daysUntil <= t) {
				return t;
			}
		}
		return null;
	}

	private void queue(CalendarEvent event, long daysUntil, int leadDays, AlertUrgency urgency) {
		Map<String, Object> payload = new HashMap<>();
		payload.put("eventId", event.getId());
		payload.put("eventType", event.getType().name());
		payload.put("title", event.getTitle());
		payload.put("eventDate", event.getEventDate().toString());
		payload.put("daysUntil", daysUntil);
		payload.put("leadDays", leadDays);
		payload.put("urgency", urgency.name());
		if (event.getTicker() != null) {
			payload.put("ticker", event.getTicker());
		}
		publisher.publish(NotificationStream.KEY, EVENT_REMINDER, payload);
	}
}
