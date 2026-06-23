package com.argus.recommendation;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * A suggested position-size band as a percentage of the portfolio (Story 6.5, FR-15). {@code reduced}
 * is true when an existing concentration in the stock has trimmed or zeroed the suggestion; the
 * {@code reasoning} always explains how the band was derived.
 *
 * @param minPercent low end of the suggested size (% of portfolio)
 * @param maxPercent high end of the suggested size
 * @param reduced    whether existing concentration cut the band
 * @param reasoning  human-readable explanation
 */
public record SizingBand(BigDecimal minPercent, BigDecimal maxPercent, boolean reduced, String reasoning) {

	static SizingBand of(double min, double max, boolean reduced, String reasoning) {
		return new SizingBand(pct(min), pct(max), reduced, reasoning);
	}

	private static BigDecimal pct(double v) {
		return BigDecimal.valueOf(Math.max(0, v)).setScale(2, RoundingMode.HALF_UP);
	}
}
