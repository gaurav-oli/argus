package com.argus.strategy;

import com.argus.intelligence.KnownUniverse;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Agent 15's hot path: where each followed ticker currently sits in the cross-section, for the strategies that
 * actually survived validation.
 *
 * <p>Percentiles are computed over the <em>whole</em> ranking universe but stored only for the tickers Argus
 * follows — the 500 index members exist to give a holding a meaningful rank, not to be tracked themselves. Only
 * {@code ACTIVE} strategies are read back, so an unvalidated or rejected paper can never reach a recommendation,
 * and each one's influence is weighted by the edge <em>Argus measured</em>, never by the t-stat its authors
 * published.
 */
@Service
public class StrategyScoreService {

	private static final Logger log = LoggerFactory.getLogger(StrategyScoreService.class);
	/** Below this the aggregate is noise, not a view. */
	static final double DEADZONE = 0.15;
	/** A stored percentile older than this is not acted on — the cross-section has moved on. */
	static final Duration MAX_AGE = Duration.ofDays(5);

	private final StrategyScoringService scoring;
	private final StrategyValidationService validation;
	private final AcademicStrategyRepository strategies;
	private final KnownUniverse followed;
	private final JdbcTemplate jdbc;
	private final boolean enabled;

	public StrategyScoreService(StrategyScoringService scoring, StrategyValidationService validation,
			AcademicStrategyRepository strategies, KnownUniverse followed, JdbcTemplate jdbc,
			@Value("${argus.strategy.enabled:true}") boolean enabled) {
		this.scoring = scoring;
		this.validation = validation;
		this.strategies = strategies;
		this.followed = followed;
		this.jdbc = jdbc;
		this.enabled = enabled;
	}

	/** Daily, after both candle passes have run. */
	@Scheduled(cron = "${argus.strategy.score-cron:0 30 19 * * *}", zone = "America/New_York")
	public void scheduled() {
		if (!enabled) {
			return;
		}
		try {
			int n = refresh();
			if (n > 0) {
				log.info("Agent 15: refreshed {} cross-sectional score(s)", n);
			}
		}
		catch (RuntimeException ex) {
			log.warn("Agent 15: score refresh failed: {}", ex.getMessage());
		}
	}

	/**
	 * Recompute today's cross-section and store the rows for followed tickers. Returns how many were written.
	 * Every implemented signal is scored (not just the active ones) so the UI can show what a strategy currently
	 * says even while it is still a candidate — but {@link #signalFor} only ever reads the active ones.
	 */
	public int refresh() {
		List<String> tickers = validation.rankingTickers();
		if (tickers.size() < 30) {
			log.debug("Agent 15: only {} ticker(s) with history — not enough cross-section to rank", tickers.size());
			return 0;
		}
		LocalDate[] span = scoring.availableSpan();
		LocalDate asOf = span[1] == null ? LocalDate.now() : span[1];
		StrategyScoringService.Scores scores = scoring.score(tickers, List.of(asOf), 0);
		java.util.Set<String> keep = followed.knownTickers();

		int written = 0;
		for (String acronym : scores.acronyms()) {
			Map<String, Double> values = scores.valuesAt(acronym, 0);
			if (values.size() < 30) {
				continue;
			}
			Map<String, Double> pct = Backtest.percentiles(values);
			for (Map.Entry<String, Double> e : pct.entrySet()) {
				if (!keep.contains(e.getKey())) {
					continue;
				}
				jdbc.update("""
						insert into strategy_score (acronym, ticker, as_of, raw_value, percentile, computed_at)
						values (?, ?, ?, ?, ?, now())
						on conflict (acronym, ticker) do update set as_of = excluded.as_of,
							raw_value = excluded.raw_value, percentile = excluded.percentile, computed_at = now()""",
						acronym, e.getKey(), asOf, values.get(e.getKey()), e.getValue());
				written++;
			}
		}
		return written;
	}

	/** One active strategy's current reading for a ticker. */
	public record Reading(String acronym, String name, String citation, double percentile, double sign, double view,
			double measuredTStat, int horizonDays, LocalDate asOf) {

		/** Plain-English direction this strategy is pointing. */
		public String direction() {
			return view > 0 ? "bullish" : view < 0 ? "bearish" : "neutral";
		}
	}

	/** What every validated strategy currently says about {@code ticker}, strongest view first. */
	public List<Reading> readingsFor(String ticker) {
		List<AcademicStrategy> active = strategies.active();
		if (active.isEmpty()) {
			return List.of();
		}
		Map<String, AcademicStrategy> byAcronym = new LinkedHashMap<>();
		active.forEach(s -> byAcronym.put(s.getAcronym(), s));
		Map<String, double[]> edge = measuredEdge(byAcronym.keySet());

		List<Reading> out = new ArrayList<>();
		jdbc.query("""
				select acronym, as_of, percentile from strategy_score
				where ticker = ? and as_of >= ? and acronym in (%s)"""
				.formatted(byAcronym.keySet().stream().map(a -> "?").reduce((a, b) -> a + "," + b).orElse("''")),
				rs -> {
					AcademicStrategy s = byAcronym.get(rs.getString("acronym"));
					if (s == null) {
						return;
					}
					double pct = rs.getDouble("percentile");
					double sign = s.signOrDefault();
					double view = sign * (pct - 0.5) * 2; // top of a +1 strategy ≈ +1, bottom ≈ -1
					double[] e = edge.getOrDefault(s.getAcronym(), new double[] {0, 30});
					out.add(new Reading(s.getAcronym(), s.getName(), s.citation(), pct, sign, view, e[0], (int) e[1],
							rs.getDate("as_of").toLocalDate()));
				},
				argsFor(ticker, byAcronym.keySet()));
		out.sort((a, b) -> Double.compare(Math.abs(b.view()), Math.abs(a.view())));
		return out;
	}

	/**
	 * The aggregate view for a ticker: each active strategy's directional reading, weighted by the out-of-sample
	 * t-statistic Argus measured for it. Empty when nothing has been validated yet or the readings cancel out —
	 * which is the honest answer, not a nudge in a random direction.
	 */
	public Optional<Aggregate> aggregateFor(String ticker) {
		List<Reading> readings = readingsFor(ticker);
		if (readings.isEmpty()) {
			return Optional.empty();
		}
		double weighted = 0;
		double weight = 0;
		for (Reading r : readings) {
			double w = Math.max(0.5, Math.min(3.0, Math.abs(r.measuredTStat())));
			weighted += r.view() * w;
			weight += w;
		}
		if (weight <= 0) {
			return Optional.empty();
		}
		double score = weighted / weight;
		return Optional.of(new Aggregate(score, readings));
	}

	/** @param score -1..+1, the weighted average of every validated strategy's view */
	public record Aggregate(double score, List<Reading> readings) {

		public boolean actionable() {
			return Math.abs(score) >= DEADZONE;
		}

		/** The strategies driving the view, for the rationale line. */
		public String rationale() {
			StringBuilder sb = new StringBuilder();
			readings.stream().limit(3).forEach(r -> sb.append(sb.isEmpty() ? "" : "; ")
					.append(String.format(Locale.ROOT, "%s (%s) %s at the %.0fth percentile", r.name(), r.citation(),
							r.direction(), r.percentile() * 100)));
			return sb.toString();
		}
	}

	/** Each strategy's best measured hold-out t-stat and the horizon it was measured at. */
	private Map<String, double[]> measuredEdge(java.util.Collection<String> acronyms) {
		Map<String, double[]> out = new LinkedHashMap<>();
		if (acronyms.isEmpty()) {
			return out;
		}
		jdbc.query("""
				select acronym, horizon_days, holdout_t_stat from strategy_backtest
				where verdict = 'PASS' and acronym in (%s)"""
				.formatted(acronyms.stream().map(a -> "?").reduce((a, b) -> a + "," + b).orElse("''")),
				rs -> {
					String acronym = rs.getString("acronym");
					double t = rs.getDouble("holdout_t_stat");
					double[] cur = out.get(acronym);
					if (cur == null || t > cur[0]) {
						out.put(acronym, new double[] {t, rs.getInt("horizon_days")});
					}
				},
				acronyms.toArray());
		return out;
	}

	private static Object[] argsFor(String ticker, java.util.Collection<String> acronyms) {
		List<Object> args = new ArrayList<>();
		args.add(ticker);
		args.add(java.sql.Date.valueOf(LocalDate.now().minusDays(MAX_AGE.toDays())));
		args.addAll(acronyms);
		return args.toArray();
	}

	/** The horizon the validated strategies were measured at — what the recommender should hold for. */
	public int typicalHorizonDays(List<Reading> readings) {
		return readings.isEmpty() ? 30
				: (int) Math.round(readings.stream().mapToInt(Reading::horizonDays).average().orElse(30));
	}
}
