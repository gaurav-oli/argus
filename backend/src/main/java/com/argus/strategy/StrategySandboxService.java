package com.argus.strategy;

import com.argus.strategy.SandboxRules.State;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Date;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * S-B7 — the strategy sandbox. A strategy that passes the hold-out backtest is enrolled in SHADOW; every day it
 * makes shadow calls on the followed tickers where its view is strong (|view| ≥ {@value #SHADOW_VIEW}), each one
 * priced against SPY at entry and resolved at its horizon. {@link SandboxRules} then moves it to CANDIDATE,
 * PROMOTED or KILLED. Only PROMOTED strategies reach live Agent 5 scoring ({@link #liveAcronyms}); shadow calls
 * never do.
 */
@Service
public class StrategySandboxService {

	private static final Logger log = LoggerFactory.getLogger(StrategySandboxService.class);
	/** A shadow call is made only where the strategy has a strong view: the top or bottom decile. */
	static final double SHADOW_VIEW = 0.8;
	/** Readings older than this are not turned into shadow calls. */
	static final int MAX_SCORE_AGE_DAYS = 5;
	/** A resolution needs a close within this many days before the target date (candles can lag a day or two). */
	static final int EXIT_TOLERANCE_DAYS = 5;

	private final JdbcTemplate jdbc;
	private final boolean enabled;

	public StrategySandboxService(JdbcTemplate jdbc, @Value("${argus.strategy.enabled:true}") boolean enabled) {
		this.jdbc = jdbc;
		this.enabled = enabled;
	}

	/** Put a strategy that just passed its backtest into SHADOW. No-op if it is already in the sandbox (any state). */
	public boolean enroll(String acronym, int horizonDays) {
		int n = jdbc.update("""
				insert into strategy_sandbox (acronym, state, horizon_days, reason) values (?, 'SHADOW', ?, ?)
				on conflict (acronym) do nothing""", acronym, horizonDays,
				"Passed the hold-out backtest at %dd — in shadow until its forward calls beat SPY.".formatted(horizonDays));
		if (n > 0) log.info("Agent 15 sandbox: {} enrolled in SHADOW ({}d)", acronym, horizonDays);
		return n > 0;
	}

	/** The strategies allowed to influence live scoring. */
	public Set<String> liveAcronyms() {
		return new HashSet<>(jdbc.queryForList("select acronym from strategy_sandbox where state = 'PROMOTED'", String.class));
	}

	/** Daily, after Agent 15's score refresh (19:30 New York). */
	@Scheduled(cron = "${argus.strategy.sandbox-cron:0 15 20 * * *}", zone = "America/New_York")
	public void scheduled() {
		if (!enabled) return;
		try {
			Pass p = pass(LocalDate.now());
			if (p.made() + p.resolved() + p.moved() > 0) {
				log.info("Agent 15 sandbox: {} shadow call(s) made, {} resolved, {} state change(s)", p.made(), p.resolved(), p.moved());
			}
		}
		catch (RuntimeException ex) {
			log.warn("Agent 15 sandbox: pass failed: {}", ex.getMessage());
		}
	}

	public record Pass(int made, int resolved, int moved) {
	}

	public Pass pass(LocalDate today) {
		int made = makeCalls(today);
		int resolved = resolveCalls(today);
		int moved = reassess();
		return new Pass(made, resolved, moved);
	}

	int makeCalls(LocalDate today) {
		record Row(String acronym, int horizon, double sign) {
		}
		List<Row> sandboxed = jdbc.query("""
				select b.acronym, b.horizon_days, coalesce(a.sign, 1) as sign
				  from strategy_sandbox b join academic_strategy a on a.acronym = b.acronym
				 where b.state in ('SHADOW', 'CANDIDATE')""",
				(rs, i) -> new Row(rs.getString("acronym"), rs.getInt("horizon_days"), rs.getDouble("sign")));
		int made = 0;
		for (Row r : sandboxed) {
			List<Object[]> readings = jdbc.query("""
					select s.ticker, s.as_of, s.percentile from strategy_score s
					 where s.acronym = ? and s.as_of >= ?
					   and not exists (select 1 from strategy_shadow_call c
					                    where c.acronym = s.acronym and c.ticker = s.ticker and c.resolved_on is null)""",
					(rs, i) -> new Object[] {rs.getString("ticker"), rs.getDate("as_of").toLocalDate(), rs.getDouble("percentile")},
					r.acronym(), Date.valueOf(today.minusDays(MAX_SCORE_AGE_DAYS)));
			for (Object[] x : readings) {
				String ticker = (String) x[0];
				LocalDate asOf = (LocalDate) x[1];
				double view = r.sign() * ((double) x[2] - 0.5) * 2;
				if (Math.abs(view) < SHADOW_VIEW) continue;
				BigDecimal entry = closeOnOrBefore(ticker, asOf, asOf.minusDays(EXIT_TOLERANCE_DAYS));
				BigDecimal spy = closeOnOrBefore("SPY", asOf, asOf.minusDays(EXIT_TOLERANCE_DAYS));
				if (entry == null || spy == null) continue;
				made += jdbc.update("""
						insert into strategy_shadow_call (acronym, ticker, direction, view, made_on, horizon_days, entry_close, spy_entry)
						values (?, ?, ?, ?, ?, ?, ?, ?) on conflict (acronym, ticker, made_on) do nothing""",
						r.acronym(), ticker, view > 0 ? "BULLISH" : "BEARISH",
						BigDecimal.valueOf(view).setScale(4, RoundingMode.HALF_UP), Date.valueOf(asOf), r.horizon(), entry, spy);
			}
		}
		return made;
	}

	int resolveCalls(LocalDate today) {
		record Open(long id, String ticker, int direction, LocalDate target, double entry, double spyEntry) {
		}
		List<Open> due = jdbc.query("""
				select id, ticker, direction, made_on + horizon_days as target, entry_close, spy_entry
				  from strategy_shadow_call
				 where resolved_on is null and made_on + horizon_days <= ?""",
				(rs, i) -> new Open(rs.getLong("id"), rs.getString("ticker"), "BEARISH".equals(rs.getString("direction")) ? -1 : 1,
						rs.getDate("target").toLocalDate(), rs.getDouble("entry_close"), rs.getDouble("spy_entry")),
				Date.valueOf(today));
		int resolved = 0;
		for (Open o : due) {
			BigDecimal exit = closeOnOrBefore(o.ticker(), o.target(), o.target().minusDays(EXIT_TOLERANCE_DAYS));
			BigDecimal spyExit = closeOnOrBefore("SPY", o.target(), o.target().minusDays(EXIT_TOLERANCE_DAYS));
			if (exit == null || spyExit == null) continue; // candles not in yet — try again tomorrow
			double excess = SandboxRules.excessPct(o.direction(), o.entry(), exit.doubleValue(), o.spyEntry(), spyExit.doubleValue());
			resolved += jdbc.update("""
					update strategy_shadow_call set resolved_on = ?, exit_close = ?, spy_exit = ?, excess_pct = ?, hit = ?
					 where id = ?""", Date.valueOf(o.target()), exit, spyExit,
					BigDecimal.valueOf(excess).setScale(4, RoundingMode.HALF_UP), excess > 0, o.id());
		}
		return resolved;
	}

	int reassess() {
		record Rec(String acronym, State state, int resolved, int hits, BigDecimal mean) {
		}
		List<Rec> recs = jdbc.query("""
				select b.acronym, b.state,
				       count(c.id) filter (where c.resolved_on is not null) as resolved,
				       count(c.id) filter (where c.hit) as hits,
				       avg(c.excess_pct) filter (where c.resolved_on is not null) as mean
				  from strategy_sandbox b left join strategy_shadow_call c on c.acronym = b.acronym
				 where b.state in ('SHADOW', 'CANDIDATE')
				 group by b.acronym, b.state""",
				(rs, i) -> new Rec(rs.getString("acronym"), State.valueOf(rs.getString("state")), rs.getInt("resolved"),
						rs.getInt("hits"), rs.getBigDecimal("mean")));
		int moved = 0;
		for (Rec r : recs) {
			SandboxRules.Decision d = SandboxRules.next(r.state(), r.resolved(), r.hits(), r.mean());
			boolean changed = d.state() != r.state();
			jdbc.update("""
					update strategy_sandbox set state = ?, reason = ?, resolved = ?, hits = ?, mean_excess_pct = ?,
					       state_changed_at = case when ? then now() else state_changed_at end
					 where acronym = ?""", d.state().name(), d.reason(), r.resolved(), r.hits(),
					r.mean() == null ? null : r.mean().setScale(4, RoundingMode.HALF_UP), changed, r.acronym());
			if (changed) {
				moved++;
				log.info("Agent 15 sandbox: {} {} → {} — {}", r.acronym(), r.state(), d.state(), d.reason());
			}
		}
		return moved;
	}

	private BigDecimal closeOnOrBefore(String ticker, LocalDate date, LocalDate notBefore) {
		List<BigDecimal> c = jdbc.queryForList("""
				select close from price_candles where ticker = ? and candle_date <= ? and candle_date >= ?
				 order by candle_date desc limit 1""", BigDecimal.class, ticker, Date.valueOf(date), Date.valueOf(notBefore));
		return c.isEmpty() ? null : c.get(0);
	}

	/** One sandboxed strategy, for the Agents page. {@code live} is true only when PROMOTED. */
	public record SandboxView(String acronym, String name, String state, boolean live, int horizonDays, int resolved, int hits,
			Integer hitPct, BigDecimal meanExcessPct, int openCalls, int neededToDecide, String reason, Instant enteredAt,
			Instant stateChangedAt) {
	}

	public List<SandboxView> list() {
		return jdbc.query("""
				select b.acronym, a.name, b.state, b.horizon_days, b.resolved, b.hits, b.mean_excess_pct, b.reason,
				       b.entered_at, b.state_changed_at,
				       (select count(*) from strategy_shadow_call c where c.acronym = b.acronym and c.resolved_on is null) as open_calls
				  from strategy_sandbox b join academic_strategy a on a.acronym = b.acronym
				 order by case b.state when 'CANDIDATE' then 0 when 'SHADOW' then 1 when 'PROMOTED' then 2 else 3 end,
				          b.resolved desc, b.acronym""",
				(rs, i) -> {
					int resolved = rs.getInt("resolved");
					int hits = rs.getInt("hits");
					String state = rs.getString("state");
					return new SandboxView(rs.getString("acronym"), rs.getString("name"), state, "PROMOTED".equals(state),
							rs.getInt("horizon_days"), resolved, hits, resolved == 0 ? null : Math.round(100f * hits / resolved),
							rs.getBigDecimal("mean_excess_pct"), rs.getInt("open_calls"),
							Math.max(0, SandboxRules.MIN_RESOLVED - resolved), rs.getString("reason"),
							rs.getTimestamp("entered_at").toInstant(), rs.getTimestamp("state_changed_at").toInstant());
				});
	}
}
