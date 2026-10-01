package com.argus.strategy;

import com.argus.marketdata.YahooChartClient;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Fills daily price history for the ranking universe — the 500-odd names Agent 15 ranks against, which are not
 * traded and so are not maintained by Agent 10's ingestion.
 *
 * <p>Three deliberate choices keep this from destabilising a box that has had ingestion trouble before:
 * <ul>
 *   <li><b>Separate from Agent 10.</b> The trading universe's ingestion keeps its own schedule and priority; this
 *       runs later and never competes with it. A failure here cannot stop candles for a held ticker.</li>
 *   <li><b>Bounded per run.</b> At most {@code argus.strategy.tickers-per-run} symbols per pass, stalest first, so
 *       a cold start spreads a 500-ticker × 10-year backfill over several nights instead of hammering Yahoo (and
 *       the Mini) in one burst.</li>
 *   <li><b>Batched inserts.</b> A 10-year backfill is ~2,500 rows per ticker; one JDBC batch with
 *       {@code on conflict do nothing} replaces 2,500 JPA round-trips.</li>
 * </ul>
 *
 * <p>Ten years of history is not excess: the long-horizon signals need it (long-run reversal reaches back 36
 * months, return seasonality 5 years, the volume trend 60 months), and a hold-out backtest needs enough monthly
 * observations on each side of the split to mean anything.
 */
@Component
public class StrategyCandleIngestion {

	private static final Logger log = LoggerFactory.getLogger(StrategyCandleIngestion.class);
	/** Yahoo publishes no cap but it is an unofficial endpoint — stay polite, same spacing Agent 10 uses. */
	private static final long REQUEST_SPACING_MS = 300;
	private static final int BATCH = 500;
	/** Enough back-to-back passes to fill a cold 500-ticker universe, bounded so a dead symbol can't loop forever. */
	private static final int MAX_CATCHUP_PASSES = 12;

	private final YahooChartClient yahoo;
	private final JdbcTemplate jdbc;
	private final StrategyUniverseService universe;
	private final int tickersPerRun;
	private final String range;
	private final boolean enabled;
	private volatile long requestSpacingMs = REQUEST_SPACING_MS;

	public StrategyCandleIngestion(YahooChartClient yahoo, JdbcTemplate jdbc, StrategyUniverseService universe,
			@Value("${argus.strategy.tickers-per-run:80}") int tickersPerRun,
			@Value("${argus.strategy.history-range:10y}") String range,
			@Value("${argus.strategy.enabled:true}") boolean enabled) {
		this.yahoo = yahoo;
		this.jdbc = jdbc;
		this.universe = universe;
		this.tickersPerRun = tickersPerRun;
		this.range = range;
		this.enabled = enabled;
	}

	/** Test hook: skip the politeness sleep. */
	void setRequestSpacingMs(long ms) {
		this.requestSpacingMs = ms;
	}

	/** Nightly, well after Agent 10's post-close run so the two never overlap. */
	@Scheduled(cron = "${argus.strategy.ingest-cron:0 45 18 * * *}", zone = "America/New_York")
	public void scheduled() {
		if (!enabled) {
			return;
		}
		try {
			int saved = ingestOnce();
			if (saved > 0) {
				log.info("Agent 15: ranking-universe candles — {} new bar(s)", saved);
			}
		}
		catch (RuntimeException ex) {
			log.warn("Agent 15: ranking-universe candle ingestion failed: {}", ex.getMessage());
		}
	}

	/**
	 * Boot catch-up. The nightly pass is deliberately bounded so it stays polite, but that would stretch a cold
	 * 500-ticker backfill over a week of nights — and nothing can be validated until the cross-section exists. So a
	 * first fill runs passes back-to-back until coverage is complete (capped, in case some symbols never resolve),
	 * on a virtual thread, after the universe has had a chance to seed. Steady state hits the first check and stops.
	 */
	@org.springframework.context.event.EventListener(org.springframework.boot.context.event.ApplicationReadyEvent.class)
	public void catchUpOnStartup() {
		if (!enabled) {
			return;
		}
		Thread.startVirtualThread(() -> {
			try {
				for (int attempt = 0; attempt < 10 && universe.activeTickers().isEmpty(); attempt++) {
					Thread.sleep(3000); // the universe seeds on its own startup thread
				}
				int total = 0;
				for (int pass = 1; pass <= MAX_CATCHUP_PASSES; pass++) {
					long covered = countWithHistory();
					int wanted = universe.activeTickers().size();
					if (covered >= wanted) {
						break;
					}
					int saved = ingestOnce();
					total += saved;
					log.info("Agent 15: startup catch-up pass {} — {} bar(s); {}/{} universe ticker(s) covered",
							pass, saved, covered, wanted);
					if (saved == 0) {
						break; // nothing left this pass could fix; the nightly run can retry
					}
				}
				if (total > 0) {
					log.info("Agent 15: startup candle catch-up saved {} bar(s) in total", total);
				}
			}
			catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
			}
			catch (RuntimeException ex) {
				log.warn("Agent 15: startup candle catch-up failed: {}", ex.getMessage());
			}
		});
	}

	/**
	 * One bounded pass, stalest first. Returns bars written. Per-ticker try/catch: one dead symbol (a ticker
	 * renamed, an index member that no longer trades) must not stop the rest of the pass.
	 */
	public int ingestOnce() {
		List<String> candidates = universe.activeTickers();
		if (candidates.isEmpty()) {
			return 0;
		}
		Map<String, LocalDate> latest = latestDates();
		LocalDate today = LocalDate.now();
		List<String> due = new ArrayList<>();
		for (String t : candidates) {
			LocalDate have = latest.get(t);
			if (have == null || have.isBefore(today.minusDays(4))) { // a long weekend is not staleness
				due.add(t);
			}
		}
		due.sort(Comparator.comparing((String t) -> latest.get(t), Comparator.nullsFirst(Comparator.naturalOrder()))
				.thenComparing(Comparator.naturalOrder()));
		if (due.size() > tickersPerRun) {
			due = due.subList(0, tickersPerRun);
		}

		int saved = 0;
		for (String ticker : due) {
			try {
				// No stored history yet → one deep backfill; otherwise a short top-up.
				saved += ingestTicker(ticker, latest.get(ticker) == null ? range : "3mo");
			}
			catch (RuntimeException ex) {
				log.debug("Agent 15: candles for {} failed: {}", ticker, ex.getMessage());
			}
			pause();
		}
		if (!due.isEmpty()) {
			long remaining = candidates.size() - countWithHistory();
			log.info("Agent 15: candle pass covered {} ticker(s), {} new bar(s); {} universe ticker(s) still without history",
					due.size(), saved, Math.max(0, remaining));
		}
		return saved;
	}

	private int ingestTicker(String ticker, String fetchRange) {
		var series = yahoo.fetch(ticker, fetchRange);
		if (series.isEmpty() || series.get().bars().isEmpty()) {
			return 0;
		}
		LocalDate liveDate = series.get().liveDate();
		List<Object[]> rows = new ArrayList<>();
		for (YahooChartClient.Bar b : series.get().bars()) {
			// Never store a still-forming session: a partial bar frozen in would corrupt every window it falls in.
			if (liveDate != null && !b.date().isBefore(liveDate)) {
				continue;
			}
			rows.add(new Object[] {ticker, b.date(), b.open(), b.high(), b.low(), b.close(), b.volume()});
		}
		int written = 0;
		for (int i = 0; i < rows.size(); i += BATCH) {
			List<Object[]> chunk = rows.subList(i, Math.min(rows.size(), i + BATCH));
			int[] counts = jdbc.batchUpdate("""
					insert into price_candles (ticker, candle_date, open, high, low, close, volume)
					values (?, ?, ?, ?, ?, ?, ?)
					on conflict (ticker, candle_date) do nothing""", chunk);
			for (int c : counts) {
				written += Math.max(0, c);
			}
		}
		return written;
	}

	/** Newest stored bar per universe ticker, in one query rather than one per ticker. */
	private Map<String, LocalDate> latestDates() {
		Map<String, LocalDate> out = new java.util.HashMap<>();
		jdbc.query("select ticker, max(candle_date) as d from price_candles group by ticker", rs -> {
			java.sql.Date d = rs.getDate("d");
			out.put(rs.getString("ticker"), d == null ? null : d.toLocalDate());
		});
		return out;
	}

	private long countWithHistory() {
		Long n = jdbc.queryForObject("""
				select count(*) from (select c.ticker from price_candles c
				join strategy_universe u on u.ticker = c.ticker and u.active
				group by c.ticker) t""", Long.class);
		return n == null ? 0 : n;
	}

	private void pause() {
		if (requestSpacingMs <= 0) {
			return;
		}
		try {
			Thread.sleep(requestSpacingMs);
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
		}
	}
}
