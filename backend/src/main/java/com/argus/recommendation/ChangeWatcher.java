package com.argus.recommendation;

import com.argus.intelligence.KnownUniverse;
import com.argus.regime.MarketRegime;
import com.argus.regime.MarketRegimeService;
import com.argus.technical.ChartStudy;
import com.argus.technical.ChartStudyService;
import com.argus.technical.LivePriceService;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Re-reviews a stock the moment something material happens to it, instead of waiting for the six-hourly pass.
 * Every couple of minutes it looks at what the agents have stored since its last look — and for each tracked
 * ticker (holdings, watchlist, open paper trades) that moved, has Agent 5 re-score it right away, which also
 * re-checks any open trade against the new call (exit on a reversal, tighter stop on a WATCH):
 *
 * <ul>
 *   <li><b>News</b> — a breaking alert naming it, or a strongly positive/negative scored headline (Agent 1);</li>
 *   <li><b>Price</b> — an intraday move of at least {@value #PRICE_SHOCK_MIN_PCT}% or {@value #PRICE_SHOCK_ATRS}× its
 *       usual daily range, against yesterday's close;</li>
 *   <li><b>Events</b> — an earnings result (Agent 7), an insider trade (Agent 4), a new company filing (Agent 14);</li>
 *   <li><b>Analysis</b> — a new Agent 11 verdict, or its thesis tracker flagging the thesis at risk;</li>
 *   <li><b>Market</b> — a broad shock (S&amp;P 500 down {@value #MARKET_SHOCK_SPY_PCT}% or VIX up
 *       {@value #MARKET_SHOCK_VIX_PCT}% on the day) re-reviews every ticker with an open trade.</li>
 * </ul>
 *
 * Guardrails: each ticker is re-reviewed at most once per {@link #COOLDOWN}, at most {@value #MAX_PER_SCAN} per
 * scan, and a price shock only fires again the same day if the move grows by half again. A frozen Agent 5 makes
 * no calls, so nothing here can trade while frozen. The trade-level guards (24h minimum hold, re-entry cooldown,
 * circuit breaker) still apply to whatever a re-review decides.
 */
@Component
public class ChangeWatcher {

	private static final Logger log = LoggerFactory.getLogger(ChangeWatcher.class);
	private static final ZoneId NEW_YORK = ZoneId.of("America/New_York");

	static final Duration COOLDOWN = Duration.ofMinutes(30);
	static final Duration FIRST_SCAN_LOOKBACK = Duration.ofMinutes(30);
	static final int MAX_PER_SCAN = 25;
	static final double STRONG_SENTIMENT = 0.6;
	static final double PRICE_SHOCK_MIN_PCT = 4.0;
	static final double PRICE_SHOCK_ATRS = 2.0;
	static final double MARKET_SHOCK_SPY_PCT = -2.0;
	static final double MARKET_SHOCK_VIX_PCT = 15.0;

	private final JdbcTemplate jdbc;
	private final RecommendationTrigger trigger;
	private final KnownUniverse universe;
	private final LivePriceService prices;
	private final ChartStudyService charts;
	private final MarketRegimeService regimes;

	/** Off in tests so no scan re-reviews tickers against the shared test database. */
	@Value("${argus.change-watcher.enabled:true}")
	private boolean enabled = true;

	private volatile Instant lastScan;
	private final Map<String, Instant> lastReviewed = new ConcurrentHashMap<>();
	/** ticker → (day, |move|) of its last price-shock trigger. */
	private final Map<String, Map.Entry<LocalDate, Double>> priceShocks = new ConcurrentHashMap<>();
	private final Set<Long> earningsSeen = ConcurrentHashMap.newKeySet();
	private final Set<String> marketShocksSeen = ConcurrentHashMap.newKeySet();

	public ChangeWatcher(JdbcTemplate jdbc, RecommendationTrigger trigger, KnownUniverse universe, LivePriceService prices,
			ChartStudyService charts, MarketRegimeService regimes) {
		this.jdbc = jdbc;
		this.trigger = trigger;
		this.universe = universe;
		this.prices = prices;
		this.charts = charts;
		this.regimes = regimes;
	}

	/** Forget all remembered state — for tests, which share one watcher. */
	void reset() {
		lastScan = null;
		lastReviewed.clear();
		priceShocks.clear();
		earningsSeen.clear();
		marketShocksSeen.clear();
	}

	@Scheduled(fixedDelay = 120_000, initialDelay = 180_000)
	public void scheduledScan() {
		if (!enabled) {
			return;
		}
		try {
			scan(Instant.now());
		}
		catch (RuntimeException ex) {
			log.warn("Change watcher scan failed: {}", ex.getMessage());
		}
	}

	/** One pass: find what changed since the last one and re-review the affected tickers. Returns them, with why. */
	Map<String, String> scan(Instant now) {
		boolean firstScan = lastScan == null; // after a boot: earnings already reported were reviewed by the catch-up
		// The first scan after a boot looks back over the startup window, so nothing stored while the app came up
		// (e.g. the hourly SEC pass that runs at boot) slips between "started" and "first look".
		Instant since = firstScan ? now.minus(FIRST_SCAN_LOOKBACK) : lastScan;
		lastScan = now;
		Set<String> tracked = tracked();
		Map<String, String> changed = new LinkedHashMap<>();

		jdbc.query("select distinct unnest(tickers), headline from breaking_alert where created_at > ? and not duplicate",
				rs -> { note(changed, tracked, rs.getString(1), "breaking news: " + rs.getString(2)); }, ts(since));
		jdbc.query("select distinct unnest(tickers), headline, sentiment_score from news_articles where analyzed_at > ? "
				+ "and abs(sentiment_score) >= ?", rs -> {
					note(changed, tracked, rs.getString(1), (rs.getDouble(3) > 0 ? "strongly positive" : "strongly negative")
							+ " news: " + rs.getString(2));
				}, ts(since), STRONG_SENTIMENT);
		jdbc.query("select ticker, transaction_type, insider_name from sec_filings where ingested_at > ?",
				rs -> { note(changed, tracked, rs.getString(1), "insider " + lower(rs.getString(2)) + " by " + rs.getString(3)); },
				ts(since));
		jdbc.query("select ticker, form from filing_digest where created_at > ?",
				rs -> { note(changed, tracked, rs.getString(1), "new " + rs.getString(2) + " filing"); }, ts(since));
		jdbc.query("select id, ticker, eps_surprise_percent from calendar_events where type = 'EARNINGS' and eps_actual is not null "
				+ "and event_date >= current_date - 3", rs -> {
					if (earningsSeen.add(rs.getLong(1)) && !firstScan) {
						Object surprise = rs.getObject(3);
						note(changed, tracked, rs.getString(2), "earnings reported"
								+ (surprise == null ? "" : String.format(Locale.ROOT, " (%+.1f%% vs estimate)", ((Number) surprise).doubleValue())));
					}
				});
		jdbc.query("select ticker, verdict from deep_analysis where status = 'DONE' and finished_at > ?",
				rs -> { note(changed, tracked, rs.getString(1), "new Agent 11 verdict: " + lower(rs.getString(2))); }, ts(since));
		jdbc.query("select ticker, thesis_reason from deep_analysis where thesis_status = 'AT_RISK' and thesis_checked_at > ?",
				rs -> { note(changed, tracked, rs.getString(1), "thesis at risk: " + rs.getString(2)); }, ts(since));
		priceShocks(changed, tracked, now);
		marketShock(changed, now);

		int reviewed = 0;
		for (Map.Entry<String, String> e : changed.entrySet()) {
			if (reviewed >= MAX_PER_SCAN) {
				log.info("Change watcher: {} more change(s) left for the next scan", changed.size() - reviewed);
				break;
			}
			Instant last = lastReviewed.get(e.getKey());
			if (last != null && last.isAfter(now.minus(COOLDOWN))) {
				continue;
			}
			lastReviewed.put(e.getKey(), now);
			reviewed++;
			log.info("Change watcher: re-reviewing {} — {}", e.getKey(), e.getValue());
			try {
				trigger.trigger(e.getKey(), "event: " + e.getValue());
			}
			catch (RuntimeException ex) {
				log.warn("Change watcher: re-review of {} failed: {}", e.getKey(), ex.toString());
			}
		}
		return changed;
	}

	private void priceShocks(Map<String, String> changed, Set<String> tracked, Instant now) {
		LocalDate today = now.atZone(NEW_YORK).toLocalDate();
		for (String t : tracked) {
			Double price = prices.livePrice(t).orElse(null);
			ChartStudy chart = price == null ? null : charts.studyFor(t).orElse(null);
			if (chart == null || chart.lastClose() <= 0 || today.equals(chart.asOf())) {
				continue; // no price, no history, or today's bar is already the reference (after-hours ingest)
			}
			double move = (price / chart.lastClose() - 1) * 100;
			double threshold = Math.max(PRICE_SHOCK_MIN_PCT, chart.atrPct() == null ? 0 : PRICE_SHOCK_ATRS * chart.atrPct());
			if (Math.abs(move) < threshold) {
				continue;
			}
			Map.Entry<LocalDate, Double> prev = priceShocks.get(t);
			if (prev != null && prev.getKey().equals(today) && Math.abs(move) < prev.getValue() * 1.5) {
				continue; // already reacted to this move today; only again if it grows by half
			}
			priceShocks.put(t, Map.entry(today, Math.abs(move)));
			note(changed, tracked, t, String.format(Locale.ROOT, "price %+.1f%% today", move));
		}
	}

	private void marketShock(Map<String, String> changed, Instant now) {
		MarketRegime r;
		try {
			r = regimes.current();
		}
		catch (RuntimeException ex) {
			return;
		}
		String day = now.atZone(NEW_YORK).toLocalDate().toString();
		String why = null;
		if (r.spy1d() != null && r.spy1d() <= MARKET_SHOCK_SPY_PCT) {
			why = String.format(Locale.ROOT, "market shock: S&P 500 %+.1f%% today", r.spy1d());
		}
		else if (r.vix1d() != null && r.vix1d() >= MARKET_SHOCK_VIX_PCT) {
			why = String.format(Locale.ROOT, "market shock: VIX %+.0f%% today", r.vix1d());
		}
		if (why == null || !marketShocksSeen.add(day)) {
			return; // one market-wide reaction per day, whichever signal fires first
		}
		for (String t : jdbc.queryForList("select distinct ticker from simulated_trades where status = 'OPEN'", String.class)) {
			changed.putIfAbsent(t, why);
		}
	}

	private Set<String> tracked() {
		Set<String> t = new HashSet<>(universe.knownTickers());
		t.addAll(jdbc.queryForList("select distinct ticker from simulated_trades where status = 'OPEN'", String.class));
		return t;
	}

	private static void note(Map<String, String> changed, Set<String> tracked, String ticker, String why) {
		if (ticker == null) {
			return;
		}
		String t = ticker.trim().toUpperCase(Locale.ROOT);
		if (tracked.contains(t)) {
			changed.putIfAbsent(t, why); // the first (most important) reason per ticker wins
		}
	}

	private static java.sql.Timestamp ts(Instant i) {
		return java.sql.Timestamp.from(i);
	}

	private static String lower(String s) {
		return s == null ? "" : s.toLowerCase(Locale.ROOT).replace('_', ' ');
	}
}
