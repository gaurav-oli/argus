package com.argus.portfolio;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Live market value + P&L for one position (Story 3.4). Trade-currency amounts plus CAD
 * equivalents; {@code price}/{@code marketValue} are null until a price tick has arrived for the
 * ticker. {@code afterHours} reflects the price's market session.
 */
public record PositionValue(
		String ticker,
		BigDecimal shares,
		BigDecimal price,
		BigDecimal marketValue,
		BigDecimal costBasis,
		BigDecimal totalPnl,
		String currency,
		BigDecimal cadMarketValue,
		BigDecimal cadPnl,
		boolean afterHours,
		Instant asOf) {
}
