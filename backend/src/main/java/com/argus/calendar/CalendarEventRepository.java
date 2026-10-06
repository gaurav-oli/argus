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

	/** Still-open rows (not yet reported — forever true for IPO, until the actual EPS lands for
	 * EARNINGS) for one ticker. A source's date ESTIMATE for the same underlying event can shift
	 * between daily runs; since {@code externalId} is date-keyed, a shift used to leave the old
	 * guess behind as a permanent duplicate. {@link Agent7CalendarService#store} supersedes these
	 * instead of accumulating them. */
	List<CalendarEvent> findBySourceAndTypeAndTickerAndEpsActualIsNull(
			String source, CalendarEventType type, String ticker);

	/** Most-recent NEW calendar event — not the same as the last run; see {@link #latestActivityAt()}. */
	@Query("select max(c.ingestedAt) from CalendarEvent c")
	Instant latestIngestedAt();

	/** When Agent 7 last completed a run (whether or not it found anything new), or null if never recorded. */
	@Query(value = "select last_run_at from agent_runs where agent_id = 'calendar'", nativeQuery = true)
	Instant lastRunAt();

	/** Agent 7 "last activity" for the Agents page and freshness alert: its last run, or its newest
	 * event if that is somehow later (e.g. before the first recorded run). */
	@Query(value = "select greatest((select max(ingested_at) from calendar_events), "
			+ "(select last_run_at from agent_runs where agent_id = 'calendar'))", nativeQuery = true)
	Instant latestActivityAt();

	/** Stamp a completed Agent 7 run. */
	@org.springframework.transaction.annotation.Transactional
	@org.springframework.data.jpa.repository.Modifying
	@Query(value = "insert into agent_runs (agent_id, last_run_at) values ('calendar', now()) "
			+ "on conflict (agent_id) do update set last_run_at = excluded.last_run_at", nativeQuery = true)
	void recordRun();

	/** Events occurring within {@code [from, to]}, soonest first — alert scans + the UI calendar. */
	List<CalendarEvent> findByEventDateBetweenOrderByEventDateAsc(LocalDate from, LocalDate to);

	/** Upcoming earnings for a ticker (the quiet-period lookup, Story 5.3). */
	List<CalendarEvent> findByTickerAndTypeAndEventDateBetweenOrderByEventDateAsc(
			String ticker, CalendarEventType type, LocalDate from, LocalDate to);

	/** Recently reported earnings within {@code [from, to]}, for the beat/miss list. */
	List<CalendarEvent> findByTypeAndEventDateBetweenOrderByEventDateAsc(
			CalendarEventType type, LocalDate from, LocalDate to);
}
