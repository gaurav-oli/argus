package com.argus.recommendation;

import com.argus.recommendation.TradeDecision.Decision;
import org.springframework.data.jpa.repository.JpaRepository;

/** Persistence for {@link TradeDecision} snapshots (Story 6.7). */
public interface TradeDecisionRepository extends JpaRepository<TradeDecision, Long> {

	/** Taken vs Declined tallies for the accuracy panel (Story 9.2). */
	long countByDecision(Decision decision);

	/** Decisions on one recommendation — outcome wiring from paper-trade closes (regret analysis). */
	java.util.List<TradeDecision> findByRecommendationId(Long recommendationId);

	/** Idempotency guard for {@code recordAgentDecision} — never overwrite an existing decision. */
	boolean existsByRecommendationId(Long recommendationId);

	/** Most-recent 100, for the Trade Journal list view (Story 11.1) — unbounded before, this table now
	 * grows continuously (every recommendation the Investor persona acts on records one), so an
	 * unlimited fetch was shipping and rendering the entire history on every page load. */
	java.util.List<TradeDecision> findTop100ByOrderByDecidedAtDesc();
}
