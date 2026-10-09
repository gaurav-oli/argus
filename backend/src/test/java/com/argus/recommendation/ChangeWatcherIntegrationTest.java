package com.argus.recommendation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.argus.TestcontainersConfiguration;
import com.argus.regime.MarketRegime;
import com.argus.regime.MarketRegimeService;
import com.argus.technical.ChartStudy;
import com.argus.technical.ChartStudyService;
import com.argus.technical.LivePriceService;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/** Each kind of material change re-reviews a tracked ticker at once — and only once per cooldown. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class ChangeWatcherIntegrationTest {

	@Autowired
	ChangeWatcher watcher;

	@Autowired
	JdbcTemplate jdbc;

	@MockitoBean
	RecommendationTrigger trigger;

	@MockitoBean
	LivePriceService prices;

	@MockitoBean
	ChartStudyService charts;

	@MockitoBean
	MarketRegimeService regimes;

	private Instant clock;

	@BeforeEach
	void setUp() {
		jdbc.update("delete from watchlist");
		jdbc.update("delete from breaking_alert");
		jdbc.update("delete from news_articles");
		jdbc.update("delete from simulated_trades");
		jdbc.update("delete from social_posts");
		jdbc.update("insert into watchlist (ticker, source, active) values ('MU', 'MANUAL', true), ('AMD', 'MANUAL', true), ('WMT', 'MANUAL', true)");
		when(prices.livePrice(anyString())).thenReturn(Optional.empty());
		when(regimes.current()).thenReturn(MarketRegime.unavailable());
		clock = Instant.now();
		watcher.reset();
		watcher.scan(clock); // the first scan establishes "since"
		reset(trigger);
	}

	private Map<String, String> nextScan(Duration later) {
		clock = clock.plus(later);
		return watcher.scan(clock);
	}

	private static ChartStudy study(double lastClose, double atrPct) {
		return new ChartStudy(250, LocalDate.now().minusDays(1), lastClose, 1.0, 2.0, 3.0, 100.0, 98.0, 90.0, ChartStudy.Trend.UPTREND,
				55.0, 0.5, 0.5, atrPct, 1.0, 1.2, 50.0, -3.0, null, null, List.of(), 1.0, 2.0, 0.2, "BULLISH", List.of());
	}

	@Test
	void aBreakingHeadlineReReviewsTheTickersItNamesThatArgusTracks() {
		jdbc.update("insert into breaking_alert (headline, tickers, reason, impact, created_at, duplicate) "
				+ "values ('Micron guides far above estimates', '{MU,ZZZZ}', 'x', 0.9, now(), false)");

		Map<String, String> changed = nextScan(Duration.ofMinutes(2));

		assertEquals(Map.of("MU", "breaking news: Micron guides far above estimates"), changed, "untracked ZZZZ is ignored");
		verify(trigger).trigger(eq("MU"), startsWith("event: breaking news"));
	}

	@Test
	void onlyAStronglyScoredHeadlineCounts() {
		jdbc.update("insert into news_articles (source, external_id, url, headline, published_at, tickers, ingested_at, sentiment_score, analyzed_at) "
				+ "values ('t', 'a', 'u1', 'AMD wins huge contract', now(), '{AMD}', now(), 0.82, now()),"
				+ "       ('t', 'b', 'u2', 'MU mentioned in passing', now(), '{MU}', now(), 0.10, now())");

		Map<String, String> changed = nextScan(Duration.ofMinutes(2));

		assertEquals(Map.of("AMD", "strongly positive news: AMD wins huge contract"), changed);
	}

	@Test
	void aTickerIsReReviewedAtMostOncePerCooldown() {
		jdbc.update("insert into breaking_alert (headline, tickers, reason, impact, created_at, duplicate) values ('first', '{MU}', 'x', 0.9, now(), false)");
		nextScan(Duration.ofMinutes(2));
		jdbc.update("insert into breaking_alert (headline, tickers, reason, impact, created_at, duplicate) values ('second', '{MU}', 'x', 0.9, now(), false)");
		nextScan(Duration.ofMinutes(2));

		verify(trigger, times(1)).trigger(eq("MU"), anyString());
	}

	@Test
	void aBigIntradayMoveReReviewsOnceAndAgainOnlyIfItGrowsByHalf() {
		when(charts.studyFor("MU")).thenReturn(Optional.of(study(100, 2.0)));
		when(prices.livePrice("MU")).thenReturn(Optional.of(105.0)); // +5% ≥ max(4%, 2×2%)
		assertTrue(nextScan(Duration.ofMinutes(2)).get("MU").startsWith("price +5.0%"));

		assertFalse(nextScan(Duration.ofMinutes(31)).containsKey("MU"), "the same move doesn't fire twice");

		when(prices.livePrice("MU")).thenReturn(Optional.of(108.0)); // +8% ≥ 1.5 × 5%
		assertTrue(nextScan(Duration.ofMinutes(31)).containsKey("MU"));

		when(prices.livePrice("AMD")).thenReturn(Optional.of(103.0));
		when(charts.studyFor("AMD")).thenReturn(Optional.of(study(100, 2.0)));
		assertFalse(nextScan(Duration.ofMinutes(31)).containsKey("AMD"), "+3% is an ordinary day");
	}

	@Test
	void aMarketShockReReviewsEveryOpenTradeOnceADay() {
		jdbc.update("insert into simulated_trades (ticker, direction, notional, entry_price, shares, horizon_days, status) "
				+ "values ('NBIS', 'BULLISH', 100, 200, 0.5, 30, 'OPEN')");
		when(regimes.current()).thenReturn(new MarketRegime(Instant.now(), -2.6, -3.1, 28.0, 22.0, 0.1, Map.of()));

		Map<String, String> changed = nextScan(Duration.ofMinutes(2));
		assertEquals("market shock: S&P 500 -2.6% today", changed.get("NBIS"));

		assertFalse(nextScan(Duration.ofMinutes(31)).containsKey("NBIS"), "once per day per shock");
		verify(trigger, never()).trigger(eq("MU"), anyString());
	}

	@Test
	void aGeopoliticalStoryReachesTheExposedSectorsOnly() {
		jdbc.update("insert into news_articles (source, external_id, url, headline, published_at, tickers, ingested_at, sentiment_score, analyzed_at) "
				+ "values ('rss', 'm1', 'u-macro', 'US announces sweeping new tariffs on Chinese semiconductors', now(), '{MACRO}', now(), -0.74, now())");

		Map<String, String> changed = nextScan(Duration.ofMinutes(2));

		assertTrue(changed.get("MU").startsWith("macro (trade & tariffs"), "chipmakers are exposed to a tariff story: " + changed);
		assertTrue(changed.containsKey("AMD"));
		assertFalse(changed.containsKey("WMT"), "consumer staples are only mildly exposed — no re-review");
	}

	@Test
	void aSocialSurgeAgainstTheTickersOwnWeekTriggersOnce() {
		for (int i = 0; i < 20; i++) {
			jdbc.update("insert into social_posts (ticker, source, external_id, body, posted_at, sentiment_score) values ('AMD', 'test', ?, 'x', now() - interval '10 minutes', 0.3)",
					"recent-" + i);
		}
		for (int i = 0; i < 30; i++) {
			jdbc.update("insert into social_posts (ticker, source, external_id, body, posted_at, sentiment_score) values ('AMD', 'test', ?, 'x', now() - interval '3 days', 0.2)",
					"old-" + i);
		}

		Map<String, String> changed = nextScan(Duration.ofMinutes(2));
		assertTrue(changed.get("AMD").startsWith("social surge: 20 posts in the last hour"), String.valueOf(changed));

		assertFalse(nextScan(Duration.ofMinutes(31)).containsKey("AMD"), "the same surge doesn't fire twice in a day");
	}

	@Test
	void bearishBreakingNewsLocksProfitOnAnOpenWinnerBeforeTheReReview() {
		jdbc.update("insert into simulated_trades (ticker, direction, entry_price, entry_at, horizon_days, status, stop_price, notional, shares) "
				+ "values ('MU', 'BULLISH', 100, now() - interval '2 days', 30, 'OPEN', 92, 100, 1)");
		when(prices.latestPrice("MU")).thenReturn(Optional.of(new java.math.BigDecimal("110")));
		jdbc.update("insert into breaking_alert (headline, tickers, reason, impact, created_at, duplicate, sentiment_label) "
				+ "values ('Micron hit with export ban', '{MU}', 'x', 0.9, now(), false, 'BEARISH')");

		nextScan(Duration.ofMinutes(2));

		assertEquals(0, new java.math.BigDecimal("105.00").compareTo(
				jdbc.queryForObject("select stop_price from simulated_trades where ticker = 'MU'", java.math.BigDecimal.class)),
				"half of the +10 profit locked");
		verify(trigger).trigger(eq("MU"), startsWith("event: breaking news"));
	}
}
