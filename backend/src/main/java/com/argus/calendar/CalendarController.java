package com.argus.calendar;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Upcoming-events endpoint for the calendar UI (Epic 5), session-gated under {@code /api/calendar}.
 * Returns events in the next {@code days} window plus earnings reported in the last
 * {@code pastEarningsDays}, latest to oldest by event date (matching the Live Alerts feed's
 * convention), annotated with the pre-earnings quiet-period posture for upcoming earnings (Story
 * 5.3), the actual-vs-estimate EPS beat/miss for reported ones, and a company logo URL where one's
 * cached ({@link CompanyLogoService}).
 */
@RestController
@RequestMapping("/api/calendar")
public class CalendarController {

	private static final ZoneId TORONTO = ZoneId.of("America/Toronto");

	private final CalendarEventRepository events;
	private final EarningsQuietPeriodService quietPeriod;
	private final CompanyLogoRepository companyLogos;

	public CalendarController(CalendarEventRepository events, EarningsQuietPeriodService quietPeriod,
			CompanyLogoRepository companyLogos) {
		this.events = events;
		this.quietPeriod = quietPeriod;
		this.companyLogos = companyLogos;
	}

	@GetMapping("/upcoming")
	public List<UpcomingEvent> upcoming(@RequestParam(required = false, defaultValue = "14") int days,
			@RequestParam(required = false, defaultValue = "30") int pastEarningsDays) {
		LocalDate today = LocalDate.now(TORONTO);
		List<CalendarEvent> past = pastEarningsDays > 0
				? events.findByTypeAndEventDateBetweenOrderByEventDateAsc(
						CalendarEventType.EARNINGS, today.minusDays(pastEarningsDays), today.minusDays(1))
				: List.of();
		List<CalendarEvent> upcoming = events.findByEventDateBetweenOrderByEventDateAsc(today, today.plusDays(days));
		List<CalendarEvent> combined = Stream.concat(past.stream(), upcoming.stream())
				.sorted(Comparator.comparing(CalendarEvent::getEventDate).reversed())
				.toList();

		Map<String, String> logoByTicker = logosFor(combined);
		return combined.stream().map(e -> toDto(e, today, logoByTicker)).toList();
	}

	private Map<String, String> logosFor(List<CalendarEvent> events) {
		Set<String> tickers = new HashSet<>();
		for (CalendarEvent e : events) {
			if (e.getTicker() != null) {
				tickers.add(e.getTicker());
			}
		}
		if (tickers.isEmpty()) {
			return Map.of();
		}
		Map<String, String> out = new HashMap<>();
		for (CompanyLogo logo : companyLogos.findAllByTickerIn(tickers)) {
			if (logo.getLogoUrl() != null) {
				out.put(logo.getTicker(), logo.getLogoUrl());
			}
		}
		return out;
	}

	private UpcomingEvent toDto(CalendarEvent e, LocalDate today, Map<String, String> logoByTicker) {
		boolean isPast = e.getEventDate().isBefore(today);
		String quiet = (!isPast && e.getType() == CalendarEventType.EARNINGS && e.getTicker() != null)
				? quietPeriod.statusFor(e.getTicker()).status().name()
				: null;
		return new UpcomingEvent(e.getId(), e.getType().name(), e.getTicker(), e.getTitle(),
				e.getEventDate(), ChronoUnit.DAYS.between(today, e.getEventDate()), quiet,
				e.getEpsActual(), e.getEpsEstimate(), e.getEpsSurprisePercent(),
				e.getTicker() != null ? logoByTicker.get(e.getTicker()) : null);
	}

	/**
	 * An event for the UI; {@code quietPeriod} is set (CLEAR/NOTE/QUIET) only for upcoming earnings.
	 * {@code epsActual}/{@code epsEstimate}/{@code epsSurprisePercent} are set only for reported
	 * earnings (null while still upcoming) — a negative {@code daysUntil} means it already happened.
	 * {@code logoUrl} is null when nothing's cached for the ticker (or the event has no ticker).
	 */
	public record UpcomingEvent(Long id, String type, String ticker, String title, LocalDate eventDate,
			long daysUntil, String quietPeriod, Double epsActual, Double epsEstimate, Double epsSurprisePercent,
			String logoUrl) {
	}
}
