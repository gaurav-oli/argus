package com.argus.calendar;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

/** Persistence for {@link CalendarEvent} rows (Story 5.1). */
public interface CalendarEventRepository extends JpaRepository<CalendarEvent, Long> {

	/** Dedup guard for Agent 7's idempotent daily run. */
	boolean existsBySourceAndExternalId(String source, String externalId);

	/** Lookup for the store-or-update path — lets a revisit backfill EPS on an existing row. */
	Optional<CalendarEvent> findBySourceAndExternalId(String source, String externalId);

	/** Most-recent calendar ingest — Agent 7 "last run" (Operations dashboard). */
	@Query("select max(c.ingestedAt) from CalendarEvent c")
	Instant latestIngestedAt();

	/** Events occurring within {@code [from, to]}, soonest first — alert scans + the UI calendar. */
	List<CalendarEvent> findByEventDateBetweenOrderByEventDateAsc(LocalDate from, LocalDate to);

	/** Upcoming earnings for a ticker (the quiet-period lookup, Story 5.3). */
	List<CalendarEvent> findByTickerAndTypeAndEventDateBetweenOrderByEventDateAsc(
			String ticker, CalendarEventType type, LocalDate from, LocalDate to);

	/** Recently reported earnings within {@code [from, to]}, for the beat/miss list. */
	List<CalendarEvent> findByTypeAndEventDateBetweenOrderByEventDateAsc(
			CalendarEventType type, LocalDate from, LocalDate to);
}
