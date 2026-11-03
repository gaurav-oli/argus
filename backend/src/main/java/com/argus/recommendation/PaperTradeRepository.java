package com.argus.recommendation;

import java.time.Instant;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

/** Persistence for recorded {@link PaperTrade} outcomes (Story 6.6). */
public interface PaperTradeRepository extends JpaRepository<PaperTrade, Long> {

	long countByWonTrue();

	/** The 10 most recent outcomes, ordered by id (monotonic) — stable even for same-millisecond inserts. */
	List<PaperTrade> findTop10ByOrderByIdDesc();

	/** Outcomes recorded since {@code since} — the 30-day accuracy window (Story 9.2). */
	long countByCreatedAtAfter(Instant since);

	long countByWonTrueAndCreatedAtAfter(Instant since);

	// ---- graduation inputs: the current system's outcomes only ----

	long countByCountsForGraduationTrue();

	long countByWonTrueAndCountsForGraduationTrue();

	List<PaperTrade> findTop10ByCountsForGraduationTrueOrderByIdDesc();

	/** Takes every outcome so far out of the graduation inputs; they stay in the all-time record. */
	@Modifying
	@Query("update PaperTrade p set p.countsForGraduation = false where p.countsForGraduation = true")
	int retireFromGraduation();
}
