package com.argus.intelligence;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BreakingAlertRepository extends JpaRepository<BreakingAlert, Long> {

	/** Dedup: has this exact headline already alerted within the cooldown window? */
	boolean existsByHeadlineAndCreatedAtAfter(String headline, Instant since);

	/** Rate limit: how many alerts have fired in the recent window? */
	long countByCreatedAtAfter(Instant since);

	/** The next alert to curate: oldest one still awaiting a dedup/summary verdict. */
	Optional<BreakingAlert> findFirstBySummaryIsNullAndDuplicateFalseOrderByCreatedAtAsc();

	/** Cards still being curated (drives the "more on the way" hint). */
	long countBySummaryIsNullAndDuplicateFalse();

	/** Ready-to-read, non-duplicate, unread alerts — the carousel's queue, most recent first. */
	List<BreakingAlert> findBySummaryIsNotNullAndDuplicateFalseAndReadFalseOrderByCreatedAtDesc();

	/** Recently-summarized, non-duplicate headlines — the "already covered" context for the dedup check. */
	List<BreakingAlert> findBySummaryIsNotNullAndDuplicateFalseAndCreatedAtAfterOrderByCreatedAtDesc(Instant since);
}
