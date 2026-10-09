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
 *   <li><b>Macro &amp; geopolitics</b> — a strongly scored macro story, or a breaking alert naming no ticker, is
 *       classified by {@link com.argus.regime.MacroTheme} (rates, oil, tariffs, conflict…); every tracked ticker
 *       whose sector is strongly exposed to that theme ({@value #MACRO_EXPOSURE}+) is re-reviewed — a tariff story
 *       reaches the chipmakers, not the utilities;</li>
 *   <li><b>Social</b> — a surge in mentions ({@value #SOCIAL_SURGE_MULTIPLE}× the usual hourly rate, at least
 *       {@value #SOCIAL_SURGE_MIN} posts in the last hour) or a sharp swing in the crowd's mood;</li>
 *   <li><b>Market</b> — a broad shock (S&amp;P 500 down {@value #MARKET_SHOCK_SPY_PCT}% or VIX up
 *       {@value #MARKET_SHOCK_VIX_PCT}% on the day) re-reviews every ticker with an open trade.</li>
 * </ul>
 *
 * <p>Each event also carries a direction where it has one (a bearish breaking alert, a negative headline, a macro
 * story bad for the ticker's sector, a sharp drop, a market shock, a souring crowd). An event adverse to an open
 * trade that is in profit first locks part of that profit ({@link PaperInvestorService#lockProfitOnAdverseEvent}) —
 * whatever the re-review then concludes.
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
	static final double MACRO_SENTIMENT = 0.5;
	static final double MACRO_EXPOSURE = 0.6;
	static final int SOCIAL_SURGE_MIN = 15;
	static final double SOCIAL_SURGE_MULTIPLE = 3.0;
	static final int SOCIAL_MOOD_MIN = 10;
	static final double SOCIAL_MOOD_SWING = 0.4;

	private final JdbcTemplate jdbc;
	private final RecommendationTrigger trigger;
	private final KnownUniverse universe;
	private final LivePriceService prices;
	private final ChartStudyService charts;
	private final MarketRegimeService regimes;
	private final com.argus.regime.SectorClassifier sectors;
	private final PaperInvestorService investor;
	/** ticker → (day, recent mention count) of its last social trigger. */
	private final Map<String, Map.Entry<LocalDate, Long>> socialSurges = new ConcurrentHashMap<>();

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
			ChartStudyService charts, MarketRegimeService regimes, com.argus.regime.SectorClassifier sectors,
			PaperInvestorService investor) {
		this.sectors = sectors;
		this.investor = investor;
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
		socialSurges.clear();
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
		Map<String, Integer> polarity = new java.util.HashMap<>(); // ticker → net direction of its events (+ good, − bad)

		jdbc.query("select distinct unnest(tickers), headline, sentiment_label from breaking_alert where created_at > ? and not duplicate",
				rs -> {
					note(changed, tracked, rs.getString(1), "breaking news: " + rs.getString(2));
					lean(polarity, tracked, rs.getString(1), "BEARISH".equals(rs.getString(3)) ? -1 : "BULLISH".equals(rs.getString(3)) ? 1 : 0);
				}, ts(since));
		jdbc.query("select distinct unnest(tickers), headline, sentiment_score from news_articles where analyzed_at > ? "
				+ "and abs(sentiment_score) >= ?", rs -> {
					note(changed, tracked, rs.getString(1), (rs.getDouble(3) > 0 ? "strongly positive" : "strongly negative")
							+ " news: " + rs.getString(2));
					lean(polarity, tracked, rs.getString(1), rs.getDouble(3) > 0 ? 1 : -1);
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
		macro(changed, polarity, tracked, since);
		socialSurges(changed, polarity, tracked, now);
		priceShocks(changed, polarity, tracked, now);
		marketShock(changed, polarity, now);

		// Protect open profit first: an adverse event locks part of it, whatever the re-review then decides.
		for (Map.Entry<String, Integer> e : polarity.entrySet()) {
			if (e.getValue() != 0) {
				try {
					investor.lockProfitOnAdverseEvent(e.getKey(), Integer.signum(e.getValue()), changed.getOrDefault(e.getKey(), "event"));
				}
				catch (RuntimeException ex) {
					log.warn("Change watcher: profit lock on {} failed: {}", e.getKey(), ex.toString());
				}
			}
		}

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

	/** A strong macro/geopolitical story reaches the tracked tickers whose sector is exposed to its theme. */
	private void macro(Map<String, String> changed, Map<String, Integer> polarity, Set<String> tracked, Instant since) {
		// headline → market-wide sentiment (+ good for markets, − bad, 0 unknown)
		Map<String, Integer> headlines = new LinkedHashMap<>();
		jdbc.query("select headline, sentiment_score from news_articles where analyzed_at > ? and 'MACRO' = any(tickers) "
				+ "and abs(sentiment_score) >= ?", rs -> { headlines.put(rs.getString(1), rs.getDouble(2) > 0 ? 1 : -1); },
				ts(since), MACRO_SENTIMENT);
		jdbc.query("select headline, sentiment_label from breaking_alert where created_at > ? and not duplicate "
				+ "and coalesce(cardinality(tickers), 0) = 0", rs -> {
					headlines.put(rs.getString(1), "BEARISH".equals(rs.getString(2)) ? -1 : "BULLISH".equals(rs.getString(2)) ? 1 : 0);
				}, ts(since));
		for (Map.Entry<String, Integer> item : headlines.entrySet()) {
			String headline = item.getKey();
			java.util.List<com.argus.regime.MacroTheme> themes = com.argus.regime.MacroTheme.classify(headline);
			if (themes.isEmpty()) {
				continue;
			}
			String label = themes.stream().map(com.argus.regime.MacroTheme::label).collect(java.util.stream.Collectors.joining(", "));
			for (String t : tracked) {
				double sensitivity = com.argus.regime.MacroTheme.meanSensitivity(themes, sectors.sectorOf(t));
				if (Math.abs(sensitivity) >= MACRO_EXPOSURE) {
					note(changed, tracked, t, "macro (" + label + "): " + headline);
					// A sector moves with the story when sensitivity > 0 (bad news → it falls), against it when < 0
					// (an oil-supply shock that hurts the market lifts energy).
					lean(polarity, tracked, t, item.getValue() * (int) Math.signum(sensitivity));
				}
			}
		}
	}

	/** An unusual burst of social chatter, or a sharp swing in its mood, against the ticker's own week. */
	private void socialSurges(Map<String, String> changed, Map<String, Integer> polarity, Set<String> tracked, Instant now) {
		LocalDate today = now.atZone(NEW_YORK).toLocalDate();
		String sql = "select ticker,"
				+ " count(*) filter (where posted_at > ?::timestamptz - interval '1 hour'),"
				+ " count(*) filter (where posted_at <= ?::timestamptz - interval '1 hour') / 167.0,"
				+ " avg(sentiment_score) filter (where posted_at > ?::timestamptz - interval '1 hour'),"
				+ " avg(sentiment_score) filter (where posted_at <= ?::timestamptz - interval '1 hour')"
				+ " from social_posts where posted_at > ?::timestamptz - interval '7 days' and posted_at <= ?::timestamptz"
				+ " group by ticker";
		jdbc.query(sql, rs -> {
			String t = rs.getString(1) == null ? null : rs.getString(1).toUpperCase(Locale.ROOT);
			if (t == null || !tracked.contains(t)) {
				return;
			}
			long recent = rs.getLong(2);
			double perHour = rs.getDouble(3);
			Double recentMood = rs.getObject(4) == null ? null : rs.getDouble(4);
			Double usualMood = rs.getObject(5) == null ? null : rs.getDouble(5);
			String why = null;
			if (recent >= SOCIAL_SURGE_MIN && recent >= SOCIAL_SURGE_MULTIPLE * Math.max(perHour, 1.0)) {
				why = String.format(Locale.ROOT, "social surge: %d posts in the last hour vs ~%.0f usual", recent, perHour);
			}
			else if (recent >= SOCIAL_MOOD_MIN && recentMood != null && usualMood != null
					&& Math.abs(recentMood - usualMood) >= SOCIAL_MOOD_SWING) {
				why = String.format(Locale.ROOT, "social mood swing: %+.2f vs %+.2f usual", recentMood, usualMood);
				lean(polarity, tracked, t, recentMood > usualMood ? 1 : -1);
			}
			if (why == null) {
				return;
			}
			Map.Entry<LocalDate, Long> prev = socialSurges.get(t);
			if (prev != null && prev.getKey().equals(today) && recent < prev.getValue() * 2) {
				return; // already reacted today; only again if the chatter doubles
			}
			socialSurges.put(t, Map.entry(today, recent));
			note(changed, tracked, t, why);
		}, ts(now), ts(now), ts(now), ts(now), ts(now), ts(now));
	}

	private void priceShocks(Map<String, String> changed, Map<String, Integer> polarity, Set<String> tracked, Instant now) {
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
			lean(polarity, tracked, t, move > 0 ? 1 : -1);
		}
	}

	private void marketShock(Map<String, String> changed, Map<String, Integer> polarity, Instant now) {
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
			polarity.merge(t, -1, Integer::sum); // a broad selloff or fear spike is bad news for every long
		}
	}

	private Set<String> tracked() {
		Set<String> t = new HashSet<>(universe.knownTickers());
		t.addAll(jdbc.queryForList("select distinct ticker from simulated_trades where status = 'OPEN'", String.class));
		return t;
	}

	/** Add an event's direction (+1 good, −1 bad, 0 none) to a tracked ticker's net lean. */
	private static void lean(Map<String, Integer> polarity, Set<String> tracked, String ticker, int direction) {
		if (ticker == null || direction == 0) {
			return;
		}
		String t = ticker.trim().toUpperCase(Locale.ROOT);
		if (tracked.contains(t)) {
			polarity.merge(t, direction, Integer::sum);
		}
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
