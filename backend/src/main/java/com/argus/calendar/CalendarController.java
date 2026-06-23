package com.argus.calendar;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Upcoming-events endpoint for the calendar UI (Epic 5), session-gated under {@code /api/calendar}.
 * Returns events in the next {@code days} window, soonest first (Story 5.1), annotated with the
 * pre-earnings quiet-period posture for earnings events (Story 5.3).
 */
@RestController
@RequestMapping("/api/calendar")
public class CalendarController {

	private static final ZoneId TORONTO = ZoneId.of("America/Toronto");

	private final CalendarEventRepository events;
	private final EarningsQuietPeriodService quietPeriod;

	public CalendarController(CalendarEventRepository events, EarningsQuietPeriodService quietPeriod) {
		this.events = events;
		this.quietPeriod = quietPeriod;
	}

	@GetMapping("/upcoming")
	public List<UpcomingEvent> upcoming(@RequestParam(required = false, defaultValue = "14") int days) {
		LocalDate today = LocalDate.now(TORONTO);
		return events.findByEventDateBetweenOrderByEventDateAsc(today, today.plusDays(days)).stream()
				.map(e -> toDto(e, today)).toList();
	}

	private UpcomingEvent toDto(CalendarEvent e, LocalDate today) {
		String quiet = (e.getType() == CalendarEventType.EARNINGS && e.getTicker() != null)
				? quietPeriod.statusFor(e.getTicker()).status().name()
				: null;
		return new UpcomingEvent(e.getId(), e.getType().name(), e.getTicker(), e.getTitle(),
				e.getEventDate(), ChronoUnit.DAYS.between(today, e.getEventDate()), quiet);
	}

	/** An upcoming event for the UI; {@code quietPeriod} is set (CLEAR/NOTE/QUIET) only for earnings. */
	public record UpcomingEvent(Long id, String type, String ticker, String title, LocalDate eventDate,
			long daysUntil, String quietPeriod) {
	}
}
