package com.argus.calendar;

import java.time.LocalDate;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/** Persistence for {@link CalendarEvent} rows (Story 5.1). */
public interface CalendarEventRepository extends JpaRepository<CalendarEvent, Long> {

	/** Dedup guard for Agent 7's idempotent daily run. */
	boolean existsBySourceAndExternalId(String source, String externalId);

	/** Events occurring within {@code [from, to]}, soonest first — alert scans + the UI calendar. */
	List<CalendarEvent> findByEventDateBetweenOrderByEventDateAsc(LocalDate from, LocalDate to);

	/** Upcoming earnings for a ticker (the quiet-period lookup, Story 5.3). */
	List<CalendarEvent> findByTickerAndTypeAndEventDateBetweenOrderByEventDateAsc(
			String ticker, CalendarEventType type, LocalDate from, LocalDate to);
}
