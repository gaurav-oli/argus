package com.argus.cost;

import java.math.BigDecimal;
import java.time.Instant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

/** Persistence for {@link CostEvent} rows (Agent 6). */
public interface CostEventRepository extends JpaRepository<CostEvent, Long> {

	/** Total paid spend (USD) since {@code since} — the month-to-date budget figure. */
	@Query("select coalesce(sum(c.costUsd), 0) from CostEvent c where c.occurredAt >= :since")
	BigDecimal sumCostSince(Instant since);

	/** Paid call count since {@code since}. */
	long countByOccurredAtAfter(Instant since);
}
