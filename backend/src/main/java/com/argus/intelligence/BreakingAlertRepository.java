package com.argus.intelligence;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface BreakingAlertRepository extends JpaRepository<BreakingAlert, Long> {

	/** Dedup: has this exact headline already alerted within the cooldown window? */
	boolean existsByHeadlineAndCreatedAtAfter(String headline, Instant since);

	/** Rate limit: how many alerts have fired in the recent window? */
	long countByCreatedAtAfter(Instant since);

	/** The next alert to curate: oldest one still awaiting a dedup/summary verdict. */
	Optional<BreakingAlert> findFirstBySummaryIsNullAndDuplicateFalseOrderByCreatedAtAsc();

	/** Cards still being curated (drives the "more on the way" hint). */
	long countBySummaryIsNullAndDuplicateFalse();

	/** Ready-to-read, non-duplicate alerts within the retention/lookback window, most recent first —
	 * both the carousel's queue (retention-days window) and the dedup "already covered" context
	 * (lookback-hours window) reuse this, just with different {@code since} cutoffs. Per-user "already
	 * dismissed" filtering happens afterward, against {@link BreakingAlertReadRepository}. */
	List<BreakingAlert> findBySummaryIsNotNullAndDuplicateFalseAndCreatedAtAfterOrderByCreatedAtDesc(Instant since);

	/** Retention: prune alerts older than the configured window. {@code breaking_alert_read} rows
	 * cascade automatically (FK ON DELETE CASCADE) — a stale alert's per-user dismissals go with it. */
	@Modifying
	@Query("delete from BreakingAlert b where b.createdAt < :cutoff")
	int deleteByCreatedAtBefore(@Param("cutoff") Instant cutoff);
}
