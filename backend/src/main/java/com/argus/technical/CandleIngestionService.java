package com.argus.technical;

import com.argus.intelligence.KnownUniverse;
import com.argus.marketdata.AlphaVantageRest;
import com.argus.marketdata.ListingResolver;
import com.argus.marketdata.YahooChartClient;
import com.argus.notification.Notification;
import com.argus.notification.NotificationService;
import com.argus.notification.UrgencyTier;
import com.argus.portfolio.LivePortfolioService;
import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Agent 10's price-history ingestion. Daily after the US close (and once at boot if any ticker has no
 * history) it pulls each known ticker's daily OHLCV, dedups against what is stored, and persists new
 * rows — <b>stalest first</b>, skipping tickers already current, so a bounded source can never starve
 * the tickers that need data most.
 *
 * <p><b>Why Yahoo first.</b> The pipeline shipped with Finnhub candles (403 on the free tier) and was
 * then moved to Alpha Vantage, where it never produced a single row: every ticker's first call asked
 * for {@code outputsize=full} (now premium-only), so no history was ever created and every later call
 * asked for {@code full} again — a deadlock — while the 25-calls/day cap could not have covered the
 * 38-ticker universe anyway. {@link YahooChartClient} is keyless, returns up to a year per call, and
 * also handles TSX names (via a {@code .TO} suffix, chosen by the live-price currency). Alpha Vantage
 * remains as a US-only fallback and only ever requests {@code compact}.
 *
 * <p>An in-progress session's bar is never stored: a partial day frozen into the table by the dedup
 * check would corrupt RSI/MACD until the next backfill.
 */
@Component
public class CandleIngestionService {

	private static final Logger log = LoggerFactory.getLogger(CandleIngestionService.class);
	private static final ObjectMapper JSON = JsonMapper.builder().build();
	private static final String TIME_SERIES_KEY = "Time Series (Daily)";
	private static final ZoneId NEW_YORK = ZoneId.of("America/New_York");
	/** Benchmark ingested alongside the universe so {@link ChartReader} can compute relative strength. */
	static final String BENCHMARK = "SPY";
	/** History depth needed for a 200-day average; below this a ticker is (re)fetched with a full year. */
	static final int FULL_HISTORY_BARS = 200;
	/** Yahoo carries no published daily cap, but it is an unofficial endpoint — stay polite. */
	private static final long REQUEST_SPACING_MS = 300;

	private final String alphaVantageKey;
	private final YahooChartClient yahoo;
	private final AlphaVantageRest alphaVantage;
	private final PriceCandleRepository candles;
	private final KnownUniverse universe;
	private final LivePortfolioService livePrices;
	private final ListingResolver listings;
	private final TechnicalAnalysisProperties props;
	private final NotificationService notifications;
	private final ChartStudyService charts;
	private volatile long requestSpacingMs = REQUEST_SPACING_MS;

	public CandleIngestionService(@Value("${argus.alpha-vantage.api-key:}") String alphaVantageKey,
			YahooChartClient yahoo, AlphaVantageRest alphaVantage, PriceCandleRepository candles,
			KnownUniverse universe, LivePortfolioService livePrices, TechnicalAnalysisProperties props,
			NotificationService notifications, ListingResolver listings, ChartStudyService charts) {
		this.charts = charts;
		this.alphaVantageKey = alphaVantageKey;
		this.yahoo = yahoo;
		this.alphaVantage = alphaVantage;
		this.candles = candles;
		this.universe = universe;
		this.livePrices = livePrices;
		this.props = props;
		this.notifications = notifications;
		this.listings = listings;
	}

	/** Test hook: skip the politeness sleep. */
	void setRequestSpacingMs(long ms) {
		this.requestSpacingMs = ms;
	}

	@Scheduled(cron = "${argus.technical.ingest-cron:0 30 16 * * *}", zone = "America/New_York")
	public void scheduledTick() {
		try {
			int saved = ingestOnce();
			long stale = staleTickerCount();
			if (saved == 0 && stale > 0) {
				alertStale(stale);
			}
		}
		catch (RuntimeException ex) {
			log.warn("Candle ingestion cycle failed: {}", ex.getMessage());
		}
	}

	/** Boot catch-up: without it a fresh deploy would wait until 16:30 ET for its first candles. */
	@EventListener(ApplicationReadyEvent.class)
	public void catchUpOnStartup() {
		Thread.startVirtualThread(() -> {
			try {
				if (staleTickerCount() > 0 || tickersToMaintain().stream()
						.anyMatch(t -> candles.countByTicker(t) < FULL_HISTORY_BARS)) {
					int saved = ingestOnce();
					log.info("Candle startup catch-up saved {} candle(s)", saved);
				}
			}
			catch (RuntimeException ex) {
				log.warn("Candle startup catch-up failed: {}", ex.getMessage());
			}
		});
	}

	/** The tracked universe plus the benchmark, deduplicated. */
	private List<String> tickersToMaintain() {
		java.util.LinkedHashSet<String> all = new java.util.LinkedHashSet<>(universe.knownTickers());
		all.add(BENCHMARK);
		return new ArrayList<>(all);
	}

	/**
	 * One ingest cycle, stalest ticker first. Public so tests (and a manual trigger) can drive it.
	 * Per-ticker try/catch: one bad symbol must not skip the rest.
	 */
	public int ingestOnce() {
		LocalDate lastSession = lastCompletedSession(ZonedDateTime.now(NEW_YORK));
		List<String> ordered = tickersToMaintain();
		Map<String, LocalDate> latest = new java.util.HashMap<>();
		for (String t : ordered) {
			latest.put(t, candles.latestDateFor(t));
		}
		ordered.sort(Comparator.comparing((String t) -> latest.get(t), Comparator.nullsFirst(Comparator.naturalOrder()))
				.thenComparing(Comparator.naturalOrder()));

		int saved = 0;
		int attempted = 0;
		for (String ticker : ordered) {
			LocalDate have = latest.get(ticker);
			boolean deepEnough = candles.countByTicker(ticker) >= FULL_HISTORY_BARS;
			if (have != null && !have.isBefore(lastSession) && deepEnough) {
				continue; // already current and deep enough for a 200-day average
			}
			try {
				attempted++;
				saved += ingestTicker(ticker, have != null && deepEnough, lastSession);
			}
			catch (RuntimeException ex) {
				log.warn("Candle ingestion for {} failed: {}", ticker, ex.getMessage());
			}
			pause();
		}
		if (saved > 0) {
			log.info("Candle ingestion: {} new candle(s) across {} ticker(s) refreshed", saved, attempted);
			charts.evictAll(); // studies built on yesterday's bars must not outlive tonight's candles
		}
		return saved;
	}

	/** Tickers whose newest stored bar is older than the last completed session. */
	long staleTickerCount() {
		LocalDate lastSession = lastCompletedSession(ZonedDateTime.now(NEW_YORK));
		return tickersToMaintain().stream().filter(t -> {
			LocalDate have = candles.latestDateFor(t);
			return have == null || have.isBefore(lastSession);
		}).count();
	}

	/** @param deepHistory whether a year of history is already stored — decides a 1-year backfill vs. a 1-month top-up */
	private int ingestTicker(String ticker, boolean deepHistory, LocalDate lastSession) {
		LocalDate oldestWanted = LocalDate.now().minusDays(props.backfillDays());
		Set<LocalDate> existing = new HashSet<>(candles.datesFor(ticker));

		Optional<YahooChartClient.Series> series = fetchYahoo(ticker, deepHistory ? "1mo" : "1y");
		if (series.isPresent() && !series.get().bars().isEmpty()) {
			int saved = 0;
			for (YahooChartClient.Bar b : series.get().bars()) {
				if (b.date().isBefore(oldestWanted) || b.date().isAfter(lastSession) || existing.contains(b.date())) {
					continue;
				}
				candles.save(new PriceCandle(ticker, b.date(), b.open(), b.high(), b.low(), b.close(), b.volume()));
				saved++;
			}
			return saved;
		}
		return alphaVantageFallback(ticker, oldestWanted, lastSession, existing);
	}

	/** Declared TSX names use their {@code .TO} listing only (a bare symbol can be a different US
	 * instrument); a CAD-quoted live price is a softer hint that tries {@code .TO} first. */
	private Optional<YahooChartClient.Series> fetchYahoo(String ticker, String range) {
		boolean liveCad = livePrices.priceCurrency(ticker).map("CAD"::equalsIgnoreCase).orElse(false);
		for (String symbol : listings.yahooSymbols(ticker, liveCad)) {
			Optional<YahooChartClient.Series> s = yahoo.fetch(symbol, range);
			if (s.isPresent() && !s.get().bars().isEmpty()) {
				return s;
			}
		}
		return Optional.empty();
	}

	/** US-only, {@code compact} only (full is premium) — a fallback, not the primary path. */
	private int alphaVantageFallback(String ticker, LocalDate oldestWanted, LocalDate lastSession,
			Set<LocalDate> existing) {
		if (alphaVantageKey == null || alphaVantageKey.isBlank()) {
			return 0;
		}
		String url = "https://www.alphavantage.co/query?function=TIME_SERIES_DAILY&symbol=" + ticker
				+ "&outputsize=compact&apikey=" + alphaVantageKey;
		Optional<String> body = alphaVantage.get(url);
		if (body.isEmpty()) {
			return 0;
		}
		JsonNode root;
		try {
			root = JSON.readTree(body.get());
		}
		catch (RuntimeException ex) {
			log.warn("Candle response parse failed for {}: {}", ticker, ex.getMessage());
			return 0;
		}
		JsonNode series = root.path(TIME_SERIES_KEY);
		if (!series.isObject()) {
			log.warn("No candle series for {}: {}", ticker, root.toString());
			return 0;
		}
		int saved = 0;
		for (Map.Entry<String, JsonNode> entry : series.properties()) {
			LocalDate date = LocalDate.parse(entry.getKey());
			if (date.isBefore(oldestWanted) || date.isAfter(lastSession) || existing.contains(date)) {
				continue;
			}
			JsonNode day = entry.getValue();
			candles.save(new PriceCandle(ticker, date,
					new BigDecimal(day.path("1. open").asString()),
					new BigDecimal(day.path("2. high").asString()),
					new BigDecimal(day.path("3. low").asString()),
					new BigDecimal(day.path("4. close").asString()),
					Long.valueOf(day.path("5. volume").asString())));
			saved++;
		}
		return saved;
	}

	/**
	 * The most recent US trading date whose bar is final: today after 16:15 ET on a weekday, otherwise
	 * the previous weekday. Holidays are not modelled — a holiday just looks like one stale day, which
	 * costs a harmless refetch.
	 */
	static LocalDate lastCompletedSession(ZonedDateTime nowNy) {
		LocalDate d = nowNy.toLocalDate();
		boolean todayFinal = isWeekday(d) && nowNy.toLocalTime().isAfter(LocalTime.of(16, 15));
		if (!todayFinal) {
			d = d.minusDays(1);
		}
		while (!isWeekday(d)) {
			d = d.minusDays(1);
		}
		return d;
	}

	private static boolean isWeekday(LocalDate d) {
		return d.getDayOfWeek() != DayOfWeek.SATURDAY && d.getDayOfWeek() != DayOfWeek.SUNDAY;
	}

	private void pause() {
		try {
			Thread.sleep(requestSpacingMs);
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
		}
	}

	/** The failure that hid for weeks: nothing ingested while tickers are stale. Surface it. */
	private void alertStale(long stale) {
		try {
			notifications.notify(Notification.of(UrgencyTier.IMPORTANT, "Price-history ingestion is stale",
					stale + " ticker(s) have no fresh daily candles — the technical (Agent 10) and "
							+ "cause-of-move (Agent 11) signals are running blind.", "/agents"));
		}
		catch (RuntimeException ex) {
			log.warn("Stale-candle alert failed: {}", ex.getMessage());
		}
	}
}
