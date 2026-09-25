package com.argus.regime;

import com.argus.marketdata.ListingResolver;
import com.argus.marketdata.YahooChartClient;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Reads the broad market (SPY, QQQ, VIX, 10-year yield, sector ETFs) and, on demand, a single stock's
 * recent move — the tape context the headline-driven agents lack. Cached briefly and warmed on a
 * schedule so a recommendation review never blocks on ~18 sequential HTTP calls; every failure
 * degrades to {@link MarketRegime#unavailable()} / empty.
 */
@Service
public class MarketRegimeService {

	private static final Logger log = LoggerFactory.getLogger(MarketRegimeService.class);
	private static final Duration TTL = Duration.ofMinutes(10);
	private static final List<String> SYMBOLS = List.of("SPY", "QQQ", "^VIX", "^TNX", "XLK", "SMH", "XLC", "XLY",
			"XLP", "XLE", "XLF", "XLV", "XLI", "XLB", "XLU", "XLRE", "GLD", "^GSPTSE");

	/** A stock's recent move, for shock/overreaction checks. */
	public record StockMove(double changePct1d, Double changePct5d) {
	}

	private record Timed<T>(T value, Instant at) {
		boolean fresh() {
			return at.isAfter(Instant.now().minus(TTL));
		}
	}

	private final YahooChartClient yahoo;
	private final ListingResolver listings;
	private volatile Timed<MarketRegime> cached;
	private final Map<String, Timed<Optional<StockMove>>> moves = new ConcurrentHashMap<>();

	public MarketRegimeService(YahooChartClient yahoo, ListingResolver listings) {
		this.yahoo = yahoo;
		this.listings = listings;
	}

	/** The current regime — cached; refreshed inline only if the cache is cold/expired. */
	public MarketRegime current() {
		Timed<MarketRegime> c = cached;
		if (c != null && c.fresh()) {
			return c.value();
		}
		return refresh();
	}

	@Scheduled(fixedDelay = 600_000, initialDelay = 20_000)
	public void warm() {
		try {
			refresh();
		}
		catch (RuntimeException ex) {
			log.warn("Market regime refresh failed: {}", ex.getMessage());
		}
	}

	private synchronized MarketRegime refresh() {
		Timed<MarketRegime> c = cached;
		if (c != null && c.fresh()) {
			return c.value();
		}
		Map<String, YahooChartClient.Series> got = new HashMap<>();
		for (String symbol : SYMBOLS) {
			yahoo.fetch(symbol, "1mo").ifPresent(s -> got.put(symbol, s));
		}
		MarketRegime regime = build(got);
		cached = new Timed<>(regime, Instant.now());
		log.info("Market regime: {} ({})", regime.label(), regime.summary());
		return regime;
	}

	/** Package-visible for tests: assemble a regime from fetched series. */
	static MarketRegime build(Map<String, YahooChartClient.Series> got) {
		YahooChartClient.Series spy = got.get("SPY");
		if (spy == null || spy.changePct1d().isEmpty()) {
			return MarketRegime.unavailable();
		}
		Map<String, Double> sectors = new HashMap<>();
		for (Sector s : Sector.values()) {
			YahooChartClient.Series ser = got.get(s.benchmark());
			if (ser != null) {
				ser.changePct1d().ifPresent(v -> sectors.put(s.benchmark(), v));
			}
		}
		return new MarketRegime(Instant.now(), spy.changePct1d().orElse(null),
				Optional.ofNullable(got.get("QQQ")).flatMap(YahooChartClient.Series::changePct1d).orElse(null),
				Optional.ofNullable(got.get("^VIX")).map(YahooChartClient.Series::livePrice)
						.map(java.math.BigDecimal::doubleValue).orElse(null),
				Optional.ofNullable(got.get("^VIX")).flatMap(YahooChartClient.Series::changePct1d).orElse(null),
				Optional.ofNullable(got.get("^TNX")).flatMap(s -> s.changePct(5)).orElse(null),
				Map.copyOf(sectors));
	}

	/** A stock's 1-day / 5-day move, cached; empty if unpriced. */
	public Optional<StockMove> moveOf(String ticker) {
		Timed<Optional<StockMove>> c = moves.get(ticker);
		if (c != null && c.fresh()) {
			return c.value();
		}
		Optional<StockMove> fresh = Optional.empty();
		for (String symbol : listings.yahooSymbols(ticker, false)) {
			fresh = yahoo.fetch(symbol, "1mo").flatMap(s -> s.changePct1d()
					.map(d1 -> new StockMove(d1, s.changePct(5).orElse(null))));
			if (fresh.isPresent()) {
				break;
			}
		}
		moves.put(ticker, new Timed<>(fresh, Instant.now()));
		return fresh;
	}
}
