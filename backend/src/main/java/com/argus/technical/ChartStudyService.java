package com.argus.technical;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;

/**
 * One place to get a ticker's {@link ChartStudy} (and the candles behind it) — shared by Agent 10's quick
 * signal, Agent 11's deep analysis, the Investor's stop levels, Agent 9's research and the Intelligence
 * page, so they all read the same chart. Studies are cached briefly: candles change once a day, and a
 * 6-hourly review touches every ticker.
 */
@Service
public class ChartStudyService {

	private static final Duration TTL = Duration.ofMinutes(30);
	private static final int BARS = 300;

	private record Cached<T>(T value, Instant at) {
		boolean fresh() {
			return at.isAfter(Instant.now().minus(TTL));
		}
	}

	private final PriceCandleRepository candles;
	private final Map<String, Cached<Optional<ChartStudy>>> studies = new ConcurrentHashMap<>();
	private volatile Cached<List<PriceCandle>> benchmark;

	public ChartStudyService(PriceCandleRepository candles) {
		this.candles = candles;
	}

	/** The chart study for {@code ticker}, or empty when there is too little history to study honestly. */
	public Optional<ChartStudy> studyFor(String ticker) {
		String t = normalize(ticker);
		Cached<Optional<ChartStudy>> c = studies.get(t);
		if (c != null && c.fresh()) {
			return c.value();
		}
		Optional<ChartStudy> fresh = ChartReader.study(ascending(t), benchmarkCandles());
		studies.put(t, new Cached<>(fresh, Instant.now()));
		return fresh;
	}

	/** All stored candles (up to ~300), oldest first — for computing indicator series on the chart. */
	public List<PriceCandle> history(String ticker) {
		return ascending(normalize(ticker));
	}

	/** Up to {@code limit} most recent candles, oldest first. */
	public List<PriceCandle> recentCandles(String ticker, int limit) {
		List<PriceCandle> all = ascending(normalize(ticker));
		return all.size() <= limit ? all : new ArrayList<>(all.subList(all.size() - limit, all.size()));
	}

	/** Drop cached studies (after a candle ingest, or in tests). */
	public void evictAll() {
		studies.clear();
		benchmark = null;
	}

	private List<PriceCandle> ascending(String ticker) {
		List<PriceCandle> recent = new ArrayList<>(candles.findTop300ByTickerOrderByCandleDateDesc(ticker));
		Collections.reverse(recent);
		return recent;
	}

	private List<PriceCandle> benchmarkCandles() {
		Cached<List<PriceCandle>> b = benchmark;
		if (b != null && b.fresh()) {
			return b.value();
		}
		List<PriceCandle> fresh = ascending(CandleIngestionService.BENCHMARK);
		benchmark = new Cached<>(fresh, Instant.now());
		return fresh;
	}

	private static String normalize(String ticker) {
		return ticker == null ? "" : ticker.trim().toUpperCase(java.util.Locale.ROOT);
	}
}
