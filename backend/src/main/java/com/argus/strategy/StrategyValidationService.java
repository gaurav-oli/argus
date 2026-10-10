package com.argus.strategy;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * The gate every published strategy has to pass before it can touch a trade: measured on Argus's own ranking
 * universe, at Argus's own holding horizons, with the last 30% of history held back.
 *
 * <p>A strategy reaching {@code ACTIVE} means it beat the hold-out bar at <em>some</em> horizon Argus actually
 * trades; everything else is {@code REJECTED} and kept in the library with the reason recorded, because knowing
 * that momentum did not work here is as useful as knowing that it did. Nothing is activated on publication, on a
 * paper's t-stat, or on in-sample performance alone.
 *
 * <p>Placebo signals — the ~114 the corpus marks as published-but-not-predictive — are never even tested: they are
 * the literature's own control group, and running them would only burn hours to rediscover that they fail.
 */
@Service
public class StrategyValidationService {

	private static final Logger log = LoggerFactory.getLogger(StrategyValidationService.class);
	/** Argus's own holding periods — a strategy is only interesting if it works over one of them. */
	static final List<Integer> HORIZONS = List.of(7, 30, 90);

	private final AcademicStrategyRepository strategies;
	private final StrategyScoringService scoring;
	private final StrategyUniverseService universe;
	private final SignalLibrary library;
	private final JdbcTemplate jdbc;
	private final boolean enabled;
	private final StrategySandboxService sandbox;

	public StrategyValidationService(AcademicStrategyRepository strategies, StrategyScoringService scoring,
			StrategyUniverseService universe, SignalLibrary library, JdbcTemplate jdbc,
			@Value("${argus.strategy.enabled:true}") boolean enabled, StrategySandboxService sandbox) {
		this.strategies = strategies;
		this.scoring = scoring;
		this.universe = universe;
		this.library = library;
		this.jdbc = jdbc;
		this.enabled = enabled;
		this.sandbox = sandbox;
	}

	/**
	 * Weekly, overnight Sunday: a full re-validation is a heavy pass (every implemented signal × every horizon over
	 * a decade of bars) and the answer moves slowly, so it has no business running more often.
	 */
	@Scheduled(cron = "${argus.strategy.validate-cron:0 0 3 * * SUN}", zone = "America/Toronto")
	public void scheduled() {
		if (!enabled) {
			return;
		}
		try {
			validateAll();
		}
		catch (RuntimeException ex) {
			log.warn("Agent 15: validation pass failed: {}", ex.getMessage());
		}
	}

	public record Outcome(String acronym, int horizonDays, Backtest.Result result) {
	}

	/**
	 * First-ever validation: the weekly cron would otherwise leave a fresh deploy's library sitting at CANDIDATE
	 * for up to a week. Waits (bounded) for the candle backfill to reach a usable cross-section, then runs once —
	 * never again after {@code strategy_backtest} has a single row, so a restart mid-backfill can't re-trigger it
	 * endlessly and the weekly cron remains the only recurring run.
	 */
	@EventListener(ApplicationReadyEvent.class)
	public void onStartup() {
		if (!enabled) {
			return;
		}
		Thread.startVirtualThread(() -> {
			try {
				Long existing = jdbc.queryForObject("select count(*) from strategy_backtest", Long.class);
				if (existing != null && existing > 0) {
					return;
				}
				for (int attempt = 0; attempt < 60; attempt++) {
					if (rankingTickers().size() >= 50) {
						break;
					}
					Thread.sleep(15_000);
				}
				List<Outcome> outcomes = validateAll();
				if (!outcomes.isEmpty()) {
					log.info("Agent 15: first-ever validation ran on startup — {} test(s)", outcomes.size());
				}
			}
			catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
			}
			catch (RuntimeException ex) {
				log.warn("Agent 15: first-ever validation failed: {}", ex.getMessage());
			}
		});
	}

	/**
	 * Backtest every implemented strategy at every horizon, persist the results, and set each strategy ACTIVE or
	 * REJECTED. Returns one outcome per strategy × horizon actually measured.
	 */
	public List<Outcome> validateAll() {
		List<String> tickers = rankingTickers();
		LocalDate[] span = scoring.availableSpan();
		if (tickers.size() < 50 || span[0] == null) {
			log.info("Agent 15: validation skipped — only {} ticker(s) with history so far", tickers.size());
			return List.of();
		}
		List<Outcome> outcomes = new ArrayList<>();
		Map<String, Backtest.Verdict> best = new LinkedHashMap<>();

		for (int horizon : HORIZONS) {
			List<LocalDate> dates = Backtest.nonOverlappingDates(span[0].plusDays(400), span[1], horizon);
			if (dates.size() < Backtest.MIN_TRAIN_OBSERVATIONS + Backtest.MIN_HOLDOUT_OBSERVATIONS) {
				log.info("Agent 15: horizon {}d has only {} non-overlapping period(s) available — skipped", horizon, dates.size());
				continue;
			}
			StrategyScoringService.Scores scores = scoring.score(tickers, dates, horizon);
			for (AcademicStrategy strategy : strategies.findByComputableTrue()) {
				if (strategy.getKind() != AcademicStrategy.Kind.PREDICTOR) {
					continue; // placebos are the literature's control group, not candidates
				}
				String acronym = strategy.getAcronym();
				if (!scores.acronyms().contains(acronym)) {
					continue;
				}
				List<Backtest.Snapshot> snapshots = new ArrayList<>();
				for (int di = 0; di < dates.size(); di++) {
					Map<String, Double> values = scores.valuesAt(acronym, di);
					if (values.isEmpty()) {
						continue;
					}
					snapshots.add(new Backtest.Snapshot(dates.get(di), Backtest.percentiles(values), scores.forwardAt(di)));
				}
				double legQuantile = strategy.getLsQuantile() == null ? 0.1 : strategy.getLsQuantile().doubleValue();
				Backtest.Result result = Backtest.run(snapshots, strategy.signOrDefault(), legQuantile);
				persist(acronym, horizon, result);
				outcomes.add(new Outcome(acronym, horizon, result));
				best.merge(acronym, result.verdict(), StrategyValidationService::better);
			}
		}

		int activated = 0;
		for (AcademicStrategy strategy : strategies.findByComputableTrue()) {
			Backtest.Verdict verdict = best.get(strategy.getAcronym());
			if (verdict == null) {
				continue;
			}
			if (verdict == Backtest.Verdict.PASS) {
				strategy.activate();
				activated++;
				// S-B7: passing the backtest earns a place in the sandbox, not live weight.
				sandbox.enroll(strategy.getAcronym(), bestPassHorizon(outcomes, strategy.getAcronym()));
			}
			else if (verdict != Backtest.Verdict.INSUFFICIENT_DATA) {
				strategy.reject();
			}
			strategies.save(strategy);
		}
		log.info("Agent 15: validation complete — {} strategy-horizon test(s), {} strategy/strategies now ACTIVE",
				outcomes.size(), activated);
		return outcomes;
	}

	/** The passing horizon with the strongest hold-out t-stat — the horizon the sandbox will shadow it at. */
	static int bestPassHorizon(List<Outcome> outcomes, String acronym) {
		return outcomes.stream()
				.filter(o -> o.acronym().equals(acronym) && o.result().verdict() == Backtest.Verdict.PASS)
				.max(java.util.Comparator.comparingDouble(o -> o.result().holdout() == null ? o.result().tStat() : o.result().holdout().tStat()))
				.map(Outcome::horizonDays).orElse(30);
	}

	/** A PASS at any horizon beats a failure at another; INSUFFICIENT_DATA never overrides a real verdict. */
	private static Backtest.Verdict better(Backtest.Verdict a, Backtest.Verdict b) {
		if (a == Backtest.Verdict.PASS || b == Backtest.Verdict.PASS) {
			return Backtest.Verdict.PASS;
		}
		return a == Backtest.Verdict.INSUFFICIENT_DATA ? b : a;
	}

	/**
	 * The tickers to rank: the ranking universe plus anything Argus actually follows, so a held name gets a
	 * percentile within the broad cross-section rather than within its own tiny group.
	 */
	public List<String> rankingTickers() {
		List<String> out = jdbc.queryForList("""
				select distinct c.ticker from price_candles c
				where c.ticker in (select ticker from strategy_universe where active)
				   or c.ticker in (select distinct ticker from positions)
				order by 1""", String.class);
		return out;
	}

	private void persist(String acronym, int horizon, Backtest.Result r) {
		jdbc.update("""
				insert into strategy_backtest (acronym, horizon_days, observations, mean_excess_pct, t_stat, win_rate,
					train_mean_excess, train_t_stat, holdout_mean_excess, holdout_t_stat, holdout_observations,
					verdict, note, first_date, last_date, run_at)
				values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now())
				on conflict (acronym, horizon_days) do update set
					observations = excluded.observations, mean_excess_pct = excluded.mean_excess_pct,
					t_stat = excluded.t_stat, win_rate = excluded.win_rate,
					train_mean_excess = excluded.train_mean_excess, train_t_stat = excluded.train_t_stat,
					holdout_mean_excess = excluded.holdout_mean_excess, holdout_t_stat = excluded.holdout_t_stat,
					holdout_observations = excluded.holdout_observations, verdict = excluded.verdict,
					note = excluded.note, first_date = excluded.first_date, last_date = excluded.last_date,
					run_at = now()""",
				acronym, horizon, r.observations(), r.meanExcessPct(), r.tStat(), r.winRate(),
				r.train() == null ? null : r.train().meanExcessPct(), r.train() == null ? null : r.train().tStat(),
				r.holdout() == null ? null : r.holdout().meanExcessPct(), r.holdout() == null ? null : r.holdout().tStat(),
				r.holdout() == null ? null : r.holdout().observations(), r.verdict().name(), r.note(),
				r.firstDate(), r.lastDate());
	}

	/** How many implemented strategies there are, for the Agents dashboard. */
	public int implementedCount() {
		return library.all().size() + 1; // + the derived industry-momentum aggregate
	}
}
