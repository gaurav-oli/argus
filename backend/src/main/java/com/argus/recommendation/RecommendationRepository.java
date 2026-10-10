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

	/** The single latest recommendation ever made for one ticker, regardless of staleness — for
	 * checking what Argus currently thinks of a stock someone actually holds (not the "current feed"
	 * window {@link #latestPerTickerSince} applies). */
	java.util.Optional<Recommendation> findFirstByTickerOrderByCreatedAtDescIdDesc(String ticker);

	/** The newest recommendation for each ticker since {@code since} — what each name currently says. */
	@Query(value = "select distinct on (ticker) * from recommendations where created_at > :since "
			+ "order by ticker, created_at desc, id desc", nativeQuery = true)
	List<Recommendation> latestPerTickerSince(@org.springframework.data.repository.query.Param("since") Instant since);

	/** For each given recommendation: when its ticker's current call (same action, unbroken) was first
	 * made — the oldest row after the last one with a different action. Rows of {@code [id, since]}. */
	@Query(value = "select l.id, (select min(r.created_at) from recommendations r where r.ticker = l.ticker "
			+ "and r.created_at <= l.created_at and r.created_at > coalesce((select max(p.created_at) "
			+ "from recommendations p where p.ticker = l.ticker and p.created_at < l.created_at "
			+ "and p.action is distinct from l.action), '-infinity'::timestamptz)) "
			+ "from recommendations l where l.id in (:ids)", nativeQuery = true)
	List<Object[]> callSince(@org.springframework.data.repository.query.Param("ids") java.util.Collection<Long> ids);

	/** Most-recent recommendation time — Agent 5 "last run" (Operations dashboard). */
	@Query("select max(r.createdAt) from Recommendation r")
	Instant latestCreatedAt();

	/** A single recommendation with its diagnostic signals eagerly loaded. */
	@org.springframework.data.jpa.repository.EntityGraph(attributePaths = "signals")
	Optional<Recommendation> findWithSignalsById(Long id);

	/** Recommendations with no AGENT {@link TradeDecision} yet — the Investor's live hook only records one at
	 * creation time, so this is what the startup backfill (and any future gap) reconciles against. */
	@Query("select r from Recommendation r where r.id not in (select d.recommendationId from TradeDecision d "
			+ "where d.source = com.argus.recommendation.TradeDecision.Source.AGENT)")
	List<Recommendation> findMissingDecision();
}
