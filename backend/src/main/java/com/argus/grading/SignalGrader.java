package com.argus.grading;

import java.time.LocalDate;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Grades every prediction, not just the few that became trades (strategy rebuild, Phase 0).
 *
 * <p>Each night it records, for every agent, its <b>last word per stock per day</b> (one prediction, not the dozens of
 * repeats a busy day produces), and the same for Agent 5's own call. Each row then gets the stock's return minus
 * SPY's at 5, 10, 20 and 60 trading days once those windows have closed. Entry is the first close on or after the
 * call date; the forward days follow SPY's trading calendar so a gap in one ticker's history can't stretch a window.
 */
@Component
public class SignalGrader {

	private static final Logger log = LoggerFactory.getLogger(SignalGrader.class);
	/** Where grading starts: the current recommendation system went live on this date. */
	public static final LocalDate GRADE_SINCE = LocalDate.of(2026, 9, 25);
	static final List<Integer> HORIZONS = List.of(5, 10, 20, 60);
	static final String BENCHMARK = "SPY";
	private static final String NY_DATE = "(r.created_at at time zone 'America/New_York')::date";

	private final JdbcTemplate jdbc;

	public SignalGrader(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	public record Result(int predictions, int calls, int entries, int graded) {
	}

	/** Record new predictions and grade every window that has closed. Idempotent. */
	public Result run() {
		Integer have = jdbc.queryForObject("select count(*) from signal_grade", Integer.class);
		// First run backfills the whole current system; after that a few days covers late or re-run reviews.
		LocalDate since = have == null || have == 0 ? GRADE_SINCE : LocalDate.now().minusDays(4);
		int predictions = recordPredictions(since);
		int calls = recordCalls(since);
		int entries = fillEntries("signal_grade") + fillEntries("rec_grade");
		int graded = 0;
		for (int h : HORIZONS) {
			graded += fillExcess("signal_grade", h) + fillExcess("rec_grade", h);
		}
		log.info("Grader: {} prediction(s), {} call(s) recorded; {} entry price(s); {} window(s) graded", predictions,
				calls, entries, graded);
		return new Result(predictions, calls, entries, graded);
	}

	int recordPredictions(LocalDate since) {
		return jdbc.update("""
				insert into signal_grade (agent, ticker, call_date, recommendation_id, direction, strength, sector, regime,
				                          earnings_week)
				select distinct on (s.agent, r.ticker, %1$s)
				       s.agent, r.ticker, %1$s, r.id, s.direction, s.weight, r.sector,
				       substring(r.features from '"regime=([A-Z_]+)"'), coalesce(r.features like '%%"earnings=soon"%%', false)
				from recommendations r
				join recommendation_signals s on s.recommendation_id = r.id
				where r.created_at >= ?::date
				order by s.agent, r.ticker, %1$s, r.created_at desc
				on conflict (agent, ticker, call_date) do update
				   set recommendation_id = excluded.recommendation_id, direction = excluded.direction,
				       strength = excluded.strength, sector = excluded.sector, regime = excluded.regime,
				       earnings_week = excluded.earnings_week
				 where signal_grade.excess_5 is null""".formatted(NY_DATE), since);
	}

	int recordCalls(LocalDate since) {
		return jdbc.update("""
				insert into rec_grade (ticker, call_date, recommendation_id, action, conviction, actionable_bar,
				                       shadow_action, shadow_conviction, sector, regime)
				select distinct on (r.ticker, %1$s)
				       r.ticker, %1$s, r.id, coalesce(r.action, 'WATCH'), r.conviction_score,
				       coalesce(r.actionable_bar, r.action in ('BUY', 'STRONG_BUY', 'AVOID', 'STRONG_AVOID')),
				       r.shadow_action, r.shadow_conviction, r.sector, substring(r.features from '"regime=([A-Z_]+)"')
				from recommendations r
				where r.created_at >= ?::date
				order by r.ticker, %1$s, r.created_at desc
				on conflict (ticker, call_date) do update
				   set recommendation_id = excluded.recommendation_id, action = excluded.action,
				       conviction = excluded.conviction, actionable_bar = excluded.actionable_bar,
				       shadow_action = excluded.shadow_action, shadow_conviction = excluded.shadow_conviction,
				       sector = excluded.sector, regime = excluded.regime
				 where rec_grade.excess_5 is null""".formatted(NY_DATE), since);
	}

	/** Entry = the first stored close on or after the call date, plus SPY's close that same day. */
	int fillEntries(String table) {
		requireTable(table);
		return jdbc.update("""
				update %1$s g set entry_date = e.candle_date, entry_close = e.close, spy_entry = e.spy
				from (select g2.id, c.candle_date, c.close::float8 as close,
				             (select b.close::float8 from price_candles b where b.ticker = '%2$s'
				               and b.candle_date <= c.candle_date order by b.candle_date desc limit 1) as spy
				      from %1$s g2
				      cross join lateral (select candle_date, close from price_candles c
				                          where c.ticker = g2.ticker and c.candle_date >= g2.call_date
				                          order by candle_date limit 1) c
				      where g2.entry_close is null) e
				where e.id = g.id and e.spy is not null""".formatted(table, BENCHMARK));
	}

	/** Fill excess_{h} = stock return − SPY return over the next {@code h} SPY trading days, once that day exists. */
	int fillExcess(String table, int h) {
		requireTable(table);
		if (!HORIZONS.contains(h)) {
			throw new IllegalArgumentException("unsupported horizon " + h);
		}
		return jdbc.update("""
				with todo as (
				    select g.id, g.ticker, g.entry_close, g.spy_entry,
				           (select b.candle_date from price_candles b where b.ticker = '%4$s' and b.candle_date > g.entry_date
				             order by b.candle_date offset %3$d limit 1) as exit_date
				    from %1$s g
				    where g.excess_%2$d is null and g.entry_close is not null and g.entry_close > 0 and g.spy_entry > 0),
				px as (
				    select t.id, t.entry_close, t.spy_entry,
				           (select c.close::float8 from price_candles c where c.ticker = t.ticker and c.candle_date <= t.exit_date
				             order by c.candle_date desc limit 1) as close_h,
				           (select b.close::float8 from price_candles b where b.ticker = '%4$s' and b.candle_date = t.exit_date) as spy_h
				    from todo t where t.exit_date is not null)
				update %1$s g set excess_%2$d = 100.0 * (px.close_h / px.entry_close - px.spy_h / px.spy_entry)
				from px where px.id = g.id and px.close_h is not null and px.spy_h is not null"""
				.formatted(table, h, h - 1, BENCHMARK));
	}

	private static void requireTable(String table) {
		if (!table.equals("signal_grade") && !table.equals("rec_grade")) {
			throw new IllegalArgumentException("not a grade table: " + table);
		}
	}
}
