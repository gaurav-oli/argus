package com.argus.calendar;

import org.springframework.data.jpa.repository.JpaRepository;

/** Persistence for fired {@link CalendarEventAlert} rows (Story 5.2). */
public interface CalendarEventAlertRepository extends JpaRepository<CalendarEventAlert, Long> {

	/** Idempotency guard: this event already had its {@code leadDays} reminder fired. */
	boolean existsByEventIdAndLeadDays(Long eventId, int leadDays);
}
