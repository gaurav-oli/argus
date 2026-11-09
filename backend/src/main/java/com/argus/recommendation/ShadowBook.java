package com.argus.recommendation;

import com.argus.grading.NightlyStep;
import com.argus.marketdata.BenchmarkPriceSource;
import com.argus.technical.ChartStudy;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Shadow trades (strategy rebuild, Phase 2): every call over the pre-registered bar gets a pretend trade with the same
 * stop, target and trailing rules the paper investor uses, and the same realistic fills. Eleven real trades in six weeks
 * can't teach anything; hundreds of shadow trades can.
 *
 * <p>They're simulated on daily candles, conservatively: a stop is checked against the day's low (high, for a short),
 * and a day that <em>opens</em> through the stop fills at the open, not the stop. Targets and the trailing ratchet are
 * evaluated at the close by {@link PositionRules}, exactly as for real trades. Shadow trades deliberately ignore cash,
 * position and sector limits — the gap between shadow and real results is what those rules cost or save.
 */
@Component
@Order(10)
public class ShadowBook implements NightlyStep {

	private static final Logger log = LoggerFactory.getLogger(ShadowBook.class);
	private static final ZoneId NY = ZoneId.of("America/New_York");
	private static final int DEFAULT_HOLD_DAYS = 30;

	private final JdbcTemplate jdbc;
	private final PaperInvestorService investor;
	private final BenchmarkPriceSource benchmark;

	public ShadowBook(JdbcTemplate jdbc, PaperInvestorService investor, BenchmarkPriceSource benchmark) {
		this.jdbc = jdbc;
		this.investor = investor;
		this.benchmark = benchmark;
	}

	@Override
	public String name() {
		return "shadow-book";
	}

	/**
	 * A new call: an actionable one opens a shadow trade (one open per ticker and direction); any actionable call closes
	 * shadow trades the other way, as thesis decay does for real ones. Best-effort — never breaks the real call.
	 */
	public void onCall(Recommendation rec, Double livePrice, ChartStudy chart) {
		try {
			if (rec.getId() == null || !Boolean.TRUE.equals(rec.getActionableBar()) || rec.getDirection() == SignalDirection.NEUTRAL
					|| livePrice == null || livePrice <= 0) {
				return;
			}
			String dir = rec.getDirection().name();
			String opposite = rec.getDirection() == SignalDirection.BULLISH ? "BEARISH" : "BULLISH";
			LocalDate today = LocalDate.now(NY);
			Double spy = benchmark.latest().map(BigDecimal::doubleValue).orElse(null);
			for (Map<String, Object> t : jdbc.queryForList(
					"select * from shadow_trades where ticker = ? and direction = ? and status = 'OPEN'", rec.getTicker(), opposite)) {
				close(t, today, livePrice, spy, "THESIS_DECAY");
			}
			Integer open = jdbc.queryForObject("select count(*) from shadow_trades where ticker = ? and direction = ? and status = 'OPEN'",
					Integer.class, rec.getTicker(), dir);
			if (open != null && open > 0) {
				return;
			}
			BigDecimal entry = BigDecimal.valueOf(livePrice);
			BigDecimal stop = StopLoss.stopFor(rec.getDirection(), chart, livePrice);
			BigDecimal target = investor.targetFor(rec, entry, chart);
			Double atr = chart == null ? null : chart.atrPct();
			jdbc.update("""
					insert into shadow_trades (recommendation_id, ticker, direction, sector, entry_at, entry_date, entry_price,
					    spy_entry, stop_price, initial_stop, target_price, high_water, atr_pct, cost_pct, hold_days)
					values (?, ?, ?, ?, now(), ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
					rec.getId(), rec.getTicker(), dir, rec.getSector(), today, livePrice, spy,
					stop == null ? null : stop.doubleValue(), stop == null ? null : stop.doubleValue(),
					target == null ? null : target.doubleValue(), livePrice, atr, FillModel.roundTripPct(livePrice, atr),
					rec.getHoldDays() == null ? DEFAULT_HOLD_DAYS : rec.getHoldDays());
		}
		catch (RuntimeException ex) {
			log.debug("Shadow trade for {} failed: {}", rec.getTicker(), ex.getMessage());
		}
	}

	/** Walk every open shadow trade forward over the daily candles that have arrived since it was last evaluated. */
	@Override
	public void run() {
		int closed = 0;
		List<Map<String, Object>> open = jdbc.queryForList("select * from shadow_trades where status = 'OPEN'");
		for (Map<String, Object> t : open) {
			try {
				if (simulate(t)) closed++;
			}
			catch (RuntimeException ex) {
				log.warn("Shadow trade {} simulation failed: {}", t.get("id"), ex.getMessage());
			}
		}
		log.info("Shadow book: {} open trade(s) walked forward, {} closed", open.size(), closed);
	}

	record Bar(LocalDate date, double open, double high, double low, double close) {
	}

	/** @return whether the trade closed */
	boolean simulate(Map<String, Object> t) {
		String ticker = (String) t.get("ticker");
		LocalDate entryDate = ((java.sql.Date) t.get("entry_date")).toLocalDate();
		LocalDate lastEval = t.get("last_eval_date") == null ? null : ((java.sql.Date) t.get("last_eval_date")).toLocalDate();
		List<Bar> bars = new ArrayList<>();
		jdbc.query("""
				select candle_date, open::float8, high::float8, low::float8, close::float8 from price_candles
				where ticker = ? and candle_date > ? order by candle_date""",
				rs -> { bars.add(new Bar(rs.getDate(1).toLocalDate(), rs.getDouble(2), rs.getDouble(3), rs.getDouble(4), rs.getDouble(5))); },
				ticker, entryDate);
		if (bars.isEmpty()) {
			return false;
		}
		boolean bull = "BULLISH".equals(t.get("direction"));
		double entry = num(t, "entry_price");
		Double stop = numOrNull(t, "stop_price");
		Double target = numOrNull(t, "target_price");
		Double hw = numOrNull(t, "high_water");
		Double atr = numOrNull(t, "atr_pct");
		boolean scaledOut = Boolean.TRUE.equals(t.get("scaled_out"));
		boolean trailed = Boolean.TRUE.equals(t.get("stop_trailed"));
		Double halfExit = numOrNull(t, "half_exit_price");
		int holdDays = ((Number) t.get("hold_days")).intValue();
		Instant entryAt = ((Timestamp) t.get("entry_at")).toInstant();

		for (int k = 0; k < bars.size(); k++) {
			Bar b = bars.get(k);
			if (lastEval != null && !b.date().isAfter(lastEval)) {
				continue;
			}
			// 1. Stop, gap-aware: a day that opens through the stop fills at the open.
			if (stop != null) {
				boolean gapped = bull ? b.open() <= stop : b.open() >= stop;
				boolean touched = bull ? b.low() <= stop : b.high() >= stop;
				if (gapped || touched) {
					finish(t, b.date(), gapped ? b.open() : stop, halfExit, trailed ? "TRAILING_STOP" : "STOP");
					return true;
				}
			}
			// 2. The paper investor's own rules at the close: take half at target, ratchet the stop.
			Instant at = b.date().atTime(16, 0).atZone(NY).toInstant();
			PositionRules.Decision d = PositionRules.evaluate(
					new PositionRules.Position(bull, entry, stop, hw, target, scaledOut, trailed, entryAt),
					new PositionRules.Market(b.close(), atr, null, null, false), at);
			if (d.action() == PositionRules.Action.EXIT) {
				finish(t, b.date(), b.close(), halfExit, d.exitReason());
				return true;
			}
			if (d.action() == PositionRules.Action.TAKE_HALF) {
				scaledOut = true;
				halfExit = b.close();
			}
			if (d.stop() != null && (stop == null || !d.stop().equals(stop))) {
				trailed = trailed || stop != null;
				stop = d.stop();
			}
			hw = d.highWater();
			// 3. Horizon.
			if (k + 1 >= holdDays) {
				finish(t, b.date(), b.close(), halfExit, "HORIZON");
				return true;
			}
		}
		jdbc.update("""
				update shadow_trades set stop_price = ?, high_water = ?, scaled_out = ?, stop_trailed = ?, half_exit_price = ?,
				    last_eval_date = ? where id = ?""", stop, hw, scaledOut, trailed, halfExit, bars.get(bars.size() - 1).date(),
				t.get("id"));
		return false;
	}

	private void finish(Map<String, Object> t, LocalDate exitDate, double exit, Double halfExit, String reason) {
		Double spy = jdbc.queryForList("select close::float8 from price_candles where ticker = 'SPY' and candle_date <= ? order by candle_date desc limit 1",
				Double.class, exitDate).stream().findFirst().orElse(null);
		t.put("half_exit_price", halfExit);
		close(t, exitDate, exit, spy, reason);
	}

	/** Close at {@code exit}: blended return if half was taken earlier, less the round-trip cost; excess vs SPY. */
	void close(Map<String, Object> t, LocalDate exitDate, double exit, Double spyExit, String reason) {
		double entry = num(t, "entry_price");
		int sign = "BULLISH".equals(t.get("direction")) ? 1 : -1;
		Double halfExit = numOrNull(t, "half_exit_price");
		double stockRet = halfExit == null ? exit / entry - 1 : 0.5 * (halfExit / entry - 1) + 0.5 * (exit / entry - 1);
		double cost = num(t, "cost_pct");
		double ret = 100 * sign * stockRet - cost;
		Double spyEntry = numOrNull(t, "spy_entry");
		Double excess = spyEntry == null || spyExit == null || spyEntry <= 0 ? null
				: 100 * sign * (stockRet - (spyExit / spyEntry - 1)) - cost;
		jdbc.update("""
				update shadow_trades set status = 'CLOSED', exit_date = ?, exit_price = ?, spy_exit = ?, exit_reason = ?,
				    return_pct = ?, excess_return_pct = ?, half_exit_price = ?, last_eval_date = ? where id = ?""",
				exitDate, exit, spyExit, reason, ret, excess, halfExit, exitDate, t.get("id"));
	}

	/** Shadow vs real summary for the Agents page. */
	public Map<String, Object> summary(int days) {
		Map<String, Object> out = new HashMap<>();
		out.put("shadow", jdbc.queryForMap("""
				select count(*) filter (where status = 'CLOSED') as closed, count(*) filter (where status = 'OPEN') as open,
				       avg(excess_return_pct) filter (where status = 'CLOSED') as avg_excess,
				       avg((excess_return_pct > 0)::int) filter (where status = 'CLOSED') as win_rate
				from shadow_trades where entry_at >= now() - make_interval(days => ?)""", days));
		out.put("real", jdbc.queryForMap("""
				select count(*) filter (where status = 'CLOSED') as closed, count(*) filter (where status = 'OPEN') as open,
				       avg(excess_return_pct) filter (where status = 'CLOSED') as avg_excess,
				       avg((excess_return_pct > 0)::int) filter (where status = 'CLOSED') as win_rate
				from simulated_trades where parent_trade_id is null and entry_at >= now() - make_interval(days => ?)""", days));
		return out;
	}

	private static double num(Map<String, Object> t, String k) {
		return ((Number) t.get(k)).doubleValue();
	}

	private static Double numOrNull(Map<String, Object> t, String k) {
		Object v = t.get(k);
		return v == null ? null : ((Number) v).doubleValue();
	}
}
