package com.argus.strategy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Agent 15's strategy library and its validation results, session-gated under {@code /api/strategies}. */
@RestController
@RequestMapping("/api/strategies")
public class StrategyController {

	private final AcademicStrategyRepository strategies;
	private final StrategyScoreService scores;
	private final StrategyValidationService validation;
	private final StrategyUniverseService universe;
	private final JdbcTemplate jdbc;

	public StrategyController(AcademicStrategyRepository strategies, StrategyScoreService scores,
			StrategyValidationService validation, StrategyUniverseService universe, JdbcTemplate jdbc) {
		this.strategies = strategies;
		this.scores = scores;
		this.validation = validation;
		this.universe = universe;
		this.jdbc = jdbc;
	}

	/** The library: what was imported, what Argus can compute, and what survived validation. */
	@GetMapping
	public LibraryView library() {
		long predictors = strategies.countByKind(AcademicStrategy.Kind.PREDICTOR.name());
		long placebos = strategies.countByKind(AcademicStrategy.Kind.PLACEBO.name());
		List<AcademicStrategy> computable = strategies.findByComputableTrue();
		List<Row> rows = new ArrayList<>();
		for (AcademicStrategy s : computable) {
			rows.add(Row.from(s, bestBacktest(s.getAcronym())));
		}
		rows.sort((a, b) -> {
			int byStatus = Integer.compare(rank(a.status()), rank(b.status()));
			return byStatus != 0 ? byStatus : Double.compare(holdoutT(b), holdoutT(a));
		});
		return new LibraryView(strategies.count(), predictors, placebos, computable.size(),
				strategies.countByStatus(AcademicStrategy.Status.ACTIVE.name()),
				strategies.countByStatus(AcademicStrategy.Status.REJECTED.name()),
				universe.activeTickers().size(), coveredTickers(), rows);
	}

	/** Every validated strategy's current read on one ticker. */
	@GetMapping("/{ticker}")
	public List<ReadingView> forTicker(@PathVariable String ticker) {
		String t = ticker.trim().toUpperCase(Locale.ROOT);
		return scores.readingsFor(t).stream().map(ReadingView::from).toList();
	}

	/** "Re-validate now" — re-runs every backtest. Slow (a decade of bars × every signal × every horizon). */
	@PostMapping("/validate")
	public ValidationSummary validate() {
		List<StrategyValidationService.Outcome> outcomes = validation.validateAll();
		long passed = outcomes.stream().filter(o -> o.result().verdict() == Backtest.Verdict.PASS).count();
		return new ValidationSummary(outcomes.size(), passed,
				strategies.countByStatus(AcademicStrategy.Status.ACTIVE.name()));
	}

	/** "Refresh scores" — recompute today's cross-section without re-running the backtests. */
	@PostMapping("/scores/refresh")
	public ValidationSummary refreshScores() {
		int written = scores.refresh();
		return new ValidationSummary(written, 0, strategies.countByStatus(AcademicStrategy.Status.ACTIVE.name()));
	}

	/** Untested strategies sort last rather than being treated as a zero edge. */
	private static double holdoutT(Row r) {
		return r.holdoutTStat() == null ? -99 : r.holdoutTStat().doubleValue();
	}

	private static int rank(String status) {
		return switch (status) {
			case "ACTIVE" -> 0;
			case "CANDIDATE" -> 1;
			case "REJECTED" -> 2;
			default -> 3;
		};
	}

	private long coveredTickers() {
		Long n = jdbc.queryForObject("""
				select count(*) from (select c.ticker from price_candles c
				join strategy_universe u on u.ticker = c.ticker and u.active group by c.ticker) t""", Long.class);
		return n == null ? 0 : n;
	}

	/** The most favourable completed test for a strategy, for the library row. */
	private BacktestRow bestBacktest(String acronym) {
		List<BacktestRow> all = jdbc.query("""
				select horizon_days, observations, mean_excess_pct, t_stat, win_rate, train_t_stat,
					holdout_mean_excess, holdout_t_stat, holdout_observations, verdict, note, first_date, last_date
				from strategy_backtest where acronym = ? order by horizon_days""",
				(rs, n) -> new BacktestRow(rs.getInt("horizon_days"), rs.getInt("observations"),
						rs.getBigDecimal("mean_excess_pct"), rs.getBigDecimal("t_stat"), rs.getBigDecimal("win_rate"),
						rs.getBigDecimal("train_t_stat"), rs.getBigDecimal("holdout_mean_excess"),
						rs.getBigDecimal("holdout_t_stat"), rs.getInt("holdout_observations"), rs.getString("verdict"),
						rs.getString("note"), date(rs.getDate("first_date")), date(rs.getDate("last_date"))),
				acronym);
		return all.stream().filter(b -> "PASS".equals(b.verdict())).findFirst()
				.orElseGet(() -> all.isEmpty() ? null : all.get(0));
	}

	private static LocalDate date(java.sql.Date d) {
		return d == null ? null : d.toLocalDate();
	}

	public record BacktestRow(int horizonDays, int observations, BigDecimal meanExcessPct, BigDecimal tStat,
			BigDecimal winRate, BigDecimal trainTStat, BigDecimal holdoutMeanExcess, BigDecimal holdoutTStat,
			int holdoutObservations, String verdict, String note, LocalDate firstDate, LocalDate lastDate) {
	}

	/**
	 * One strategy in the library. {@code publishedTStat} is what the paper reported — shown for context and
	 * deliberately never used to decide anything; {@code holdoutTStat} is what Argus measured out-of-sample.
	 */
	public record Row(String acronym, String name, String citation, String definition, String dataCategory,
			String economicCategory, String replicationGrade, BigDecimal publishedTStat, BigDecimal sign,
			String status, String deviation, Integer horizonDays, BigDecimal measuredExcessPct, BigDecimal tStat,
			BigDecimal holdoutMeanExcess, BigDecimal holdoutTStat, Integer observations, String verdict, String note) {

		static Row from(AcademicStrategy s, BacktestRow b) {
			return new Row(s.getAcronym(), s.getName(), s.citation(), s.getDefinition(), s.getDataCategory(),
					s.getEconomicCategory(), s.getReplicationGrade(), s.getPublishedTStat(), s.getSign(),
					s.getStatus().name(), null, b == null ? null : b.horizonDays(), b == null ? null : b.meanExcessPct(),
					b == null ? null : b.tStat(), b == null ? null : b.holdoutMeanExcess(),
					b == null ? null : b.holdoutTStat(), b == null ? null : b.observations(),
					b == null ? null : b.verdict(), b == null ? null : b.note());
		}
	}

	/**
	 * @param universeSize  how many names the cross-section ranks over
	 * @param universeCovered how many of those actually have stored history yet
	 */
	public record LibraryView(long total, long predictors, long placebos, long computable, long active, long rejected,
			long universeSize, long universeCovered, List<Row> strategies) {
	}

	public record ValidationSummary(int tests, long passed, long active) {
	}

	public record ReadingView(String acronym, String name, String citation, double percentile, double sign,
			double view, String direction, double measuredTStat, int horizonDays, LocalDate asOf) {

		static ReadingView from(StrategyScoreService.Reading r) {
			return new ReadingView(r.acronym(), r.name(), r.citation(), r.percentile(), r.sign(), r.view(),
					r.direction(), r.measuredTStat(), r.horizonDays(), r.asOf());
		}
	}
}
