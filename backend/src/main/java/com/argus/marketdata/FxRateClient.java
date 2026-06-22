package com.argus.marketdata;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

/**
 * Fetches a single historical USD/CAD rate from an upstream source (Story 3.2). Implementations do
 * the raw network call only; caching lives in {@link FxRateService}. A lookup that can't be
 * resolved returns empty (the caller then flags the lot {@code fxEstimated}) — never throws.
 */
public interface FxRateClient {

	/** USD/CAD on the given date, resolving to the nearest prior business day; empty if unavailable. */
	Optional<BigDecimal> usdCadOn(LocalDate date);

	/** Short identifier recorded with cached rates (e.g. {@code bankofcanada-valet}). */
	String sourceName();
}
