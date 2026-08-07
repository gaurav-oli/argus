package com.argus.cost;

import java.time.Instant;
import org.springframework.data.jpa.repository.JpaRepository;

/** Persistence for {@link LocalModelCall} rows (Agent 6). */
public interface LocalModelCallRepository extends JpaRepository<LocalModelCall, Long> {

	/** Local-model call count since {@code since} — the month-to-date figure for the Cost Governor panel. */
	long countByOccurredAtAfter(Instant since);
}
