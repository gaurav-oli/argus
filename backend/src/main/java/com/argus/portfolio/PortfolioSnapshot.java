package com.argus.portfolio;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * A live portfolio valuation pushed to {@code /topic/portfolio} (Story 3.4). Totals are in CAD (the
 * Canadian investor's home currency); {@code anyAfterHours} is true if any priced position is using
 * an after-hours price. Transient — never persisted.
 */
public record PortfolioSnapshot(
		BigDecimal totalValueCad,
		BigDecimal totalCostCad,
		BigDecimal totalPnlCad,
		BigDecimal totalValueUsd,
		boolean anyAfterHours,
		Instant asOf,
		List<PositionValue> positions) {
}
