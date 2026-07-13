package com.argus.intelligence;

import java.time.Instant;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BreakingAlertRepository extends JpaRepository<BreakingAlert, Long> {

	/** Dedup: has this exact headline already alerted within the cooldown window? */
	boolean existsByHeadlineAndCreatedAtAfter(String headline, Instant since);

	/** Rate limit: how many alerts have fired in the recent window? */
	long countByCreatedAtAfter(Instant since);

	/** Recent alerts, newest first (for the in-app feed). */
	List<BreakingAlert> findTop20ByOrderByCreatedAtDesc();
}
