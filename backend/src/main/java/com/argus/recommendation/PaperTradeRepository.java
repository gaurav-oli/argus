package com.argus.recommendation;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/** Persistence for recorded {@link PaperTrade} outcomes (Story 6.6). */
public interface PaperTradeRepository extends JpaRepository<PaperTrade, Long> {

	long countByWonTrue();

	/** The 10 most recent outcomes, ordered by id (monotonic) — stable even for same-millisecond inserts. */
	List<PaperTrade> findTop10ByOrderByIdDesc();
}
