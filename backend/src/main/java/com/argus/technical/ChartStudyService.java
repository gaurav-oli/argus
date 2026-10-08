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

	private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ChartStudyService.class);

	private static final Duration TTL = Duration.ofMinutes(30);
	private static final int BARS = 300;

	private record Cached<T>(T value, Instant at) {
		boolean fresh() {
			return at.isAfter(Instant.now().minus(TTL));
		}
	}

	private final PriceCandleRepository candles;
	private final Map<String, Cached<Optional<ChartStudy>>> studies = new ConcurrentHashMap<>();
	/** The candles each cached study was built from, so a live re-measure of its levels needs no query. */
	private final Map<String, Cached<List<PriceCandle>>> series = new ConcurrentHashMap<>();
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
		List<PriceCandle> bars = ascending(t);
		Optional<ChartStudy> fresh;
		try {
			fresh = ChartReader.study(bars, benchmarkCandles());
		}
		catch (RuntimeException ex) {
			// One ticker's bad data must never throw into its callers (fundamentals, Agent 5's review, the
			// investor): before, a single malformed ASTS candle stopped all three for days.
			log.warn("Chart study for {} failed — treating it as having no chart: {}", t, ex.toString());
			fresh = Optional.empty();
		}
		Instant now = Instant.now();
		studies.put(t, new Cached<>(fresh, now));
		series.put(t, new Cached<>(bars, now));
		return fresh;
	}

	/** A live price further than this from the last close is distrusted (e.g. a wrong-listing quote). */
	static final double MAX_LIVE_DRIFT = 0.25;

	/**
	 * The study with support/resistance re-measured from {@code livePrice}, so a stock that broke a level
	 * today isn't shown on the wrong side of it until tonight's candle. Falls back to the close-based
	 * study when there is no live price or it is implausibly far from the last close.
	 */
	public Optional<ChartStudy> studyFor(String ticker, Double livePrice) {
		return studyFor(ticker).map(s -> withLivePrice(s, ticker, livePrice));
	}

	/** Whether {@code livePrice} is usable against {@code study}: present, positive and near the last close. */
	public static boolean livePriceUsable(ChartStudy study, Double livePrice) {
		return livePrice != null && livePrice > 0 && study.lastClose() > 0
				&& Math.abs(livePrice / study.lastClose() - 1) <= MAX_LIVE_DRIFT;
	}

	ChartStudy withLivePrice(ChartStudy study, String ticker, Double livePrice) {
		if (!livePriceUsable(study, livePrice) || livePrice.doubleValue() == study.lastClose()) {
			return study;
		}
		String t = normalize(ticker);
		Cached<List<PriceCandle>> bars = series.get(t);
		List<PriceCandle> candles = bars != null && bars.fresh() ? bars.value() : ascending(t);
		return study.withLevels(ChartReader.levels(candles, livePrice), livePrice);
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
		series.clear();
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
