package com.argus.marketdata;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

/**
 * Seam for a live price source (Stories 3.4/3.5). Decouples the value engine from the transport so
 * the engine is testable without a socket. The only implementation is the key-gated Finnhub feed;
 * a future provider plugs in here. Symbols are supplied lazily so the feed can (re)subscribe to the
 * current holdings.
 */
public interface PriceFeed {

	/**
	 * Begin streaming. Deliver each trade to {@code ticks}; seed each symbol's previous close (for
	 * day P&L) to {@code previousClose}. {@code symbols} yields the tickers to watch.
	 */
	void start(Supplier<Collection<String>> symbols, PriceTick ticks,
			BiConsumer<String, BigDecimal> previousClose);

	void stop();

	/** A single price observation. */
	@FunctionalInterface
	interface PriceTick {
		void onTick(String ticker, BigDecimal price, Instant at);
	}
}
