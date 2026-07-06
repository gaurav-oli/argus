package com.argus.portfolio;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Live market value + P&L for one position (Stories 3.4/3.5). Trade-currency amounts plus CAD
 * equivalents; {@code price}/{@code marketValue} are null until a price tick has arrived, and
 * {@code dayPnl} is null until a previous close is known. {@code weightPercent} is the position's
 * share of total portfolio CAD value.
 */
public record PositionValue(
		String ticker,
		String companyName,
		BigDecimal shares,
		BigDecimal price,
		BigDecimal marketValue,
		BigDecimal costBasis,
		BigDecimal totalPnl,
		BigDecimal totalPnlPercent,
		BigDecimal previousClose,
		BigDecimal dayPnl,
		BigDecimal dayPnlPercent,
		String currency,
		BigDecimal cadMarketValue,
		BigDecimal cadPnl,
		BigDecimal weightPercent,
		boolean afterHours,
		Instant asOf,
		String institution,
		String account,
		Long id,
		BigDecimal usdMarketValue,
		BigDecimal cadAcb,
		boolean fxEstimated) {

	/** Return a copy with {@code weightPercent} set (computed once portfolio totals are known). */
	PositionValue withWeight(BigDecimal weightPercent) {
		return new PositionValue(ticker, companyName, shares, price, marketValue, costBasis, totalPnl,
				totalPnlPercent, previousClose, dayPnl, dayPnlPercent, currency, cadMarketValue, cadPnl,
				weightPercent, afterHours, asOf, institution, account, id, usdMarketValue, cadAcb, fxEstimated);
	}
}
