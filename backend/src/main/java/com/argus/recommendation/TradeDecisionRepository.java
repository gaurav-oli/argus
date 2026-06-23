package com.argus.recommendation;

import org.springframework.data.jpa.repository.JpaRepository;

/** Persistence for {@link TradeDecision} snapshots (Story 6.7). */
public interface TradeDecisionRepository extends JpaRepository<TradeDecision, Long> {
}
