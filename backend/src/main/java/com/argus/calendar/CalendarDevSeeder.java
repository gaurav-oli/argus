package com.argus.calendar;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Seeds a few upcoming calendar events on first dev run (Epic 5) so the calendar UI and the
 * quiet-period badge have content without a live Finnhub key. Dev-profile only, gated by
 * {@code argus.dev.seed} (off in tests), and idempotent (no-ops once any event exists).
 */
@Component
@Profile("dev")
@ConditionalOnProperty(name = "argus.dev.seed", havingValue = "true")
public class CalendarDevSeeder {

	private static final Logger log = LoggerFactory.getLogger(CalendarDevSeeder.class);
	private static final ZoneId TORONTO = ZoneId.of("America/Toronto");

	private final CalendarEventRepository events;

	public CalendarDevSeeder(CalendarEventRepository events) {
		this.events = events;
	}

	@EventListener(ApplicationReadyEvent.class)
	public void seed() {
		if (events.count() > 0) {
			return;
		}
		LocalDate today = LocalDate.now(TORONTO);
		events.saveAll(List.of(
				earnings("AAPL", today.plusDays(2)),   // near — quiet period
				earnings("MSFT", today.plusDays(4)),   // within 5 days — noted in bear scenario
				new CalendarEvent(CalendarEventType.FED, null, "FOMC rate decision", today.plusDays(8),
						"fed-rss", "seed-fed-1"),
				earnings("NVDA", today.plusDays(12))));
		log.info("Seeded {} calendar events", events.count());
	}

	private CalendarEvent earnings(String ticker, LocalDate date) {
		return new CalendarEvent(CalendarEventType.EARNINGS, ticker, ticker + " earnings", date,
				"finnhub-earnings", "seed-earnings-" + ticker);
	}
}
