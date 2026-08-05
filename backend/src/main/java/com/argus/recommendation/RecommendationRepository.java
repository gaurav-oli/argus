package com.argus.recommendation;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

/** Persistence for {@link Recommendation} aggregates (Story 6.2). */
public interface RecommendationRepository extends JpaRepository<Recommendation, Long> {

	/** Recommendations newest-first for the feed/UI. */
	List<Recommendation> findTop50ByOrderByCreatedAtDesc();

	/** Most-recent recommendation time — Agent 5 "last run" (Operations dashboard). */
	@Query("select max(r.createdAt) from Recommendation r")
	Instant latestCreatedAt();

	/** A single recommendation with its diagnostic signals eagerly loaded. */
	@org.springframework.data.jpa.repository.EntityGraph(attributePaths = "signals")
	Optional<Recommendation> findWithSignalsById(Long id);

	/** Recommendations with no {@link TradeDecision} yet — the Investor's live hook only records one at
	 * creation time, so this is what the startup backfill (and any future gap) reconciles against. */
	@Query("select r from Recommendation r where r.id not in (select d.recommendationId from TradeDecision d)")
	List<Recommendation> findMissingDecision();
}
