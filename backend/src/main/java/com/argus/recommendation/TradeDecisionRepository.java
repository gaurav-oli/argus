package com.argus.recommendation;

import com.argus.recommendation.TradeDecision.Decision;
import org.springframework.data.jpa.repository.JpaRepository;

/** Persistence for {@link TradeDecision} snapshots (Story 6.7). */
public interface TradeDecisionRepository extends JpaRepository<TradeDecision, Long> {

	/** Taken vs Declined tallies for the accuracy panel (Story 9.2). */
	long countByDecision(Decision decision);
}
