package com.argus.strategy;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Computes every implemented signal for every ticker, at one date (the live ranking) or at many (a backtest).
 *
 * <p>History is streamed one ticker at a time: a ticker's bars are loaded, every signal and every forward return is
 * taken from them, and the series is dropped before the next ticker. Results live in primitive matrices rather than
 * maps of boxed doubles, which is what keeps a 540-ticker × 10-year × 15-signal pass inside a sane heap on a Mac
 * Mini that is also running Postgres, Redis and Ollama.
 *
 * <p>No look-ahead: a signal at date {@code d} only ever reads bars on or before {@code d}, and the forward return
 * it is judged against is strictly after it.
 */
@Service
public class StrategyScoringService {

	private static final Logger log = LoggerFactory.getLogger(StrategyScoringService.class);
	/** Industry momentum is an aggregate over a sector's members, so it is derived here, not in the library. */
	public static final String INDUSTRY_MOMENTUM = "IndMom";
	static final String BENCHMARK = "SPY";

	private final JdbcTemplate jdbc;
	private final SignalLibrary library;
	private final StrategyUniverseService universe;

	public StrategyScoringService(JdbcTemplate jdbc, SignalLibrary library, StrategyUniverseService universe) {
		this.jdbc = jdbc;
		this.library = library;
		this.universe = universe;
	}

	/**
	 * Signal values and realized forward returns across a ticker × date grid. {@code NaN} means "not computable
	 * here" (too little history, no volume, a dead symbol) and is skipped by every consumer rather than defaulted.
	 */
	public static final class Scores {

		private final List<String> tickers;
		private final List<LocalDate> dates;
		private final Map<String, Integer> tickerIndex = new LinkedHashMap<>();
		private final Map<String, double[][]> byAcronym = new LinkedHashMap<>();
		private final double[][] forwardReturn;

		Scores(List<String> tickers, List<LocalDate> dates, List<String> acronyms) {
			this.tickers = List.copyOf(tickers);
			this.dates = List.copyOf(dates);
			for (int i = 0; i < this.tickers.size(); i++) {
				tickerIndex.put(this.tickers.get(i), i);
			}
			for (String a : acronyms) {
				double[][] m = new double[dates.size()][tickers.size()];
				for (double[] row : m) {
					Arrays.fill(row, Double.NaN);
				}
				byAcronym.put(a, m);
			}
			this.forwardReturn = new double[dates.size()][tickers.size()];
			for (double[] row : forwardReturn) {
				Arrays.fill(row, Double.NaN);
			}
		}

		void put(String acronym, int dateIdx, int tickerIdx, double value) {
			double[][] m = byAcronym.get(acronym);
			if (m != null) {
				m[dateIdx][tickerIdx] = value;
			}
		}

		void putForward(int dateIdx, int tickerIdx, double value) {
			forwardReturn[dateIdx][tickerIdx] = value;
		}

		public List<String> tickers() {
			return tickers;
		}

		public List<LocalDate> dates() {
			return dates;
		}

		public java.util.Set<String> acronyms() {
			return byAcronym.keySet();
		}

		/** Raw signal values for one acronym at one date, ticker → value, NaNs omitted. */
		public Map<String, Double> valuesAt(String acronym, int dateIdx) {
			Map<String, Double> out = new LinkedHashMap<>();
			double[][] m = byAcronym.get(acronym);
			if (m == null) {
				return out;
			}
			for (int i = 0; i < tickers.size(); i++) {
				if (!Double.isNaN(m[dateIdx][i])) {
					out.put(tickers.get(i), m[dateIdx][i]);
				}
			}
			return out;
		}

		/** Realized forward returns at one date, ticker → return, NaNs omitted. */
		public Map<String, Double> forwardAt(int dateIdx) {
			Map<String, Double> out = new LinkedHashMap<>();
			for (int i = 0; i < tickers.size(); i++) {
				if (!Double.isNaN(forwardReturn[dateIdx][i])) {
					out.put(tickers.get(i), forwardReturn[dateIdx][i]);
				}
			}
			return out;
		}

		Integer indexOf(String ticker) {
			return tickerIndex.get(ticker);
		}

		double value(String acronym, int dateIdx, int tickerIdx) {
			double[][] m = byAcronym.get(acronym);
			return m == null ? Double.NaN : m[dateIdx][tickerIdx];
		}
	}

	/**
	 * Score {@code tickers} at {@code dates}, measuring each forward return over {@code horizonDays} (pass 0 to
	 * skip forward returns, as the live path does).
	 */
	public Scores score(List<String> tickers, List<LocalDate> dates, int horizonDays) {
		List<String> acronyms = new ArrayList<>(library.implementedAcronyms());
		acronyms.add(INDUSTRY_MOMENTUM);
		Scores scores = new Scores(tickers, dates, acronyms);
		Series benchmark = loadSeries(BENCHMARK);

		int scored = 0;
		for (int ti = 0; ti < tickers.size(); ti++) {
			String ticker = tickers.get(ti);
			Series series;
			try {
				series = loadSeries(ticker);
			}
			catch (RuntimeException ex) {
				log.debug("Agent 15: could not load history for {}: {}", ticker, ex.getMessage());
				continue;
			}
			if (series.isEmpty()) {
				continue;
			}
			scored++;
			for (int di = 0; di < dates.size(); di++) {
				LocalDate asOf = dates.get(di);
				for (SignalLibrary.Signal signal : library.all()) {
					if (series.bars().size() < signal.minBars()) {
						continue;
					}
					try {
						OptionalDouble v = signal.computation().apply(series, benchmark, asOf);
						if (v.isPresent() && Double.isFinite(v.getAsDouble())) {
							scores.put(signal.acronym(), di, ti, v.getAsDouble());
						}
					}
					catch (RuntimeException ex) {
						// A single signal failing on a single ticker-date is not worth failing the pass over.
						log.trace("Agent 15: {} failed for {} at {}: {}", signal.acronym(), ticker, asOf, ex.getMessage());
					}
				}
				if (horizonDays > 0) {
					OptionalDouble fwd = forwardReturn(series, asOf, horizonDays);
					if (fwd.isPresent()) {
						scores.putForward(di, ti, fwd.getAsDouble());
					}
				}
			}
		}
		deriveIndustryMomentum(scores);
		log.info("Agent 15: scored {} signal(s) across {} ticker(s) with history and {} date(s)",
				acronyms.size(), scored, dates.size());
		return scores;
	}

	/**
	 * Industry momentum (Grinblatt &amp; Moskowitz 1999): each ticker takes its sector's average 6-month return.
	 * Equal-weighted rather than market-value-weighted — Argus has no shares-outstanding data, and that deviation is
	 * recorded on the strategy row rather than hidden.
	 */
	private void deriveIndustryMomentum(Scores scores) {
		Map<String, String> sectors = universe.sectorsByTicker();
		if (sectors.isEmpty()) {
			return;
		}
		for (int di = 0; di < scores.dates().size(); di++) {
			Map<String, List<Double>> bySector = new LinkedHashMap<>();
			for (int ti = 0; ti < scores.tickers().size(); ti++) {
				String sector = sectors.get(scores.tickers().get(ti));
				double mom = scores.value("Mom6m", di, ti);
				if (sector != null && !Double.isNaN(mom)) {
					bySector.computeIfAbsent(sector, k -> new ArrayList<>()).add(mom);
				}
			}
			Map<String, Double> sectorMean = new LinkedHashMap<>();
			bySector.forEach((sector, values) -> {
				if (values.size() >= 3) { // a "sector" of one or two names is not an industry read
					sectorMean.put(sector, Series.mean(values).orElse(Double.NaN));
				}
			});
			for (int ti = 0; ti < scores.tickers().size(); ti++) {
				Double mean = sectorMean.get(sectors.get(scores.tickers().get(ti)));
				if (mean != null && Double.isFinite(mean)) {
					scores.put(INDUSTRY_MOMENTUM, di, ti, mean);
				}
			}
		}
	}

	private static OptionalDouble forwardReturn(Series series, LocalDate asOf, int horizonDays) {
		OptionalDouble entry = series.closeOnOrBefore(asOf);
		OptionalDouble exit = series.closeOnOrBefore(asOf.plusDays(horizonDays));
		if (entry.isEmpty() || exit.isEmpty() || entry.getAsDouble() <= 0) {
			return OptionalDouble.empty();
		}
		// closeOnOrBefore(asOf + h) would silently return the entry price itself if no later bar exists.
		LocalDate last = series.lastDate();
		if (last == null || last.isBefore(asOf.plusDays(horizonDays))) {
			return OptionalDouble.empty();
		}
		return OptionalDouble.of(exit.getAsDouble() / entry.getAsDouble() - 1);
	}

	/** One ticker's full stored history, ascending. Read with plain JDBC: this is a bulk path, not an entity path. */
	public Series loadSeries(String ticker) {
		List<Series.Bar> bars = jdbc.query("""
				select candle_date, close, high, low, coalesce(volume, 0) as volume
				from price_candles where ticker = ? order by candle_date""",
				(rs, n) -> new Series.Bar(rs.getDate("candle_date").toLocalDate(), rs.getDouble("close"),
						rs.getDouble("high"), rs.getDouble("low"), rs.getDouble("volume")),
				ticker);
		return new Series(bars);
	}

	/** The earliest and latest stored bar across the whole candle table — the backtest's available span. */
	public LocalDate[] availableSpan() {
		return jdbc.query("select min(candle_date) as lo, max(candle_date) as hi from price_candles", rs -> {
			if (!rs.next()) {
				return new LocalDate[] {null, null};
			}
			java.sql.Date lo = rs.getDate("lo");
			java.sql.Date hi = rs.getDate("hi");
			return new LocalDate[] {lo == null ? null : lo.toLocalDate(), hi == null ? null : hi.toLocalDate()};
		});
	}
}
