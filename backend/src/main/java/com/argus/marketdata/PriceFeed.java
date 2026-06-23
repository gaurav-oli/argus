package com.argus.marketdata;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.function.Supplier;

/**
 * Seam for a live price source (Story 3.4). Decouples the value engine from the transport so the
 * engine is testable without a socket. The only implementation is the key-gated Finnhub WS; a
 * future provider plugs in here. Symbols are supplied lazily so the feed can (re)subscribe to the
 * current holdings.
 */
public interface PriceFeed {

	/** Begin streaming; deliver each trade to {@code handler}. {@code symbols} yields tickers to watch. */
	void start(Supplier<Collection<String>> symbols, PriceTick handler);

	void stop();

	/** A single price observation. */
	@FunctionalInterface
	interface PriceTick {
		void onTick(String ticker, BigDecimal price, Instant at);
	}
}
