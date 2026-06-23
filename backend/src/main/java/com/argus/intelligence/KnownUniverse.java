package com.argus.intelligence;

import java.util.Set;

/**
 * The set of tickers the user already follows — anything NOT in here is a "stranger" for the
 * Stranger Danger Protocol (Story 4.4). The MVP universe is the current portfolio holdings
 * ({@link PortfolioKnownUniverse}); the interface is the seam for a future watchlist provider to
 * widen it (e.g. a {@code CompositeKnownUniverse}) without touching the detector.
 */
public interface KnownUniverse {

	/** Known tickers, normalized upper-case. */
	Set<String> knownTickers();

	default boolean isKnown(String ticker) {
		return ticker != null && knownTickers().contains(ticker.trim().toUpperCase());
	}
}
