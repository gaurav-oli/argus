package com.argus.recommendation;

import com.argus.recommendation.TradeDecision.Decision;
import org.springframework.data.jpa.repository.JpaRepository;

/** Persistence for {@link TradeDecision} snapshots (Story 6.7). */
public interface TradeDecisionRepository extends JpaRepository<TradeDecision, Long> {

	/** Taken vs Declined tallies for the accuracy panel (Story 9.2). */
	long countByDecision(Decision decision);

	/** Decisions on one recommendation — outcome wiring from paper-trade closes (regret analysis). */
	java.util.List<TradeDecision> findByRecommendationId(Long recommendationId);

	/** Idempotency guard for {@code recordAgentDecision}: the Investor records one decision per recommendation.
	 * (S-C1: a person's own Take/Decline no longer blocks it — that decision is theirs, not the system's.) */
	boolean existsByRecommendationIdAndSource(Long recommendationId, TradeDecision.Source source);

	/** S-C1: one person's own decision on a recommendation (their latest), if any. */
	java.util.Optional<TradeDecision> findFirstByRecommendationIdAndSourceAndUserIdOrderByDecidedAtDesc(Long recommendationId,
			TradeDecision.Source source, Long userId);

	/** S-C1: the journal one person sees — the shared Investor decisions plus their own, most recent first. A null
	 * {@code userId} (no signed-in person, e.g. a test or job) sees decisions with no owner. */
	@org.springframework.data.jpa.repository.Query("""
			select d from TradeDecision d
			 where d.source = com.argus.recommendation.TradeDecision.Source.AGENT
			    or d.userId = :userId
			    or (:userId is null and d.userId is null)
			 order by d.decidedAt desc""")
	java.util.List<TradeDecision> findJournal(@org.springframework.data.repository.query.Param("userId") Long userId,
			org.springframework.data.domain.Limit limit);

}
