package com.argus.grading;

import com.argus.grading.StrategyGateService.Status;
import com.argus.regime.MacroEventCalendar;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * The macro-event short as a shadow strategy (strategy rebuild, Phase 4): short SPY at the close before each FOMC
 * decision / jobs report, cover at the release-day close, less {@link #COST_PCT}. It never trades. After
 * {@link #RELEASES} releases it's dropped (FAILED) unless the total beats zero after costs; if it does beat zero it
 * stays in shadow flagged for a human review rather than trading on its own.
 */
@Component
public class MacroShortShadow implements GateEvaluator {

	private static final Logger log = LoggerFactory.getLogger(MacroShortShadow.class);
	static final int RELEASES = 12;
	/** Round trip on SPY plus a day's borrow. */
	static final double COST_PCT = 0.10;
	/** Tracking starts at deployment of the rebuild; earlier releases are covered by the 10-year backtest. */
	static final LocalDate TRACK_FROM = LocalDate.of(2026, 11, 9);

	private final JdbcTemplate jdbc;
	private final MacroEventCalendar calendar;
	private final StrategyGateService gates;

	public MacroShortShadow(JdbcTemplate jdbc, MacroEventCalendar calendar, StrategyGateService gates) {
		this.jdbc = jdbc;
		this.calendar = calendar;
		this.gates = gates;
	}

	@Override
	public String gate() {
		return StrategyGateService.PHASE4_MACRO_SHORT;
	}

	@Override
	public void evaluate() {
		if (gates.status(gate()) == Status.FAILED) {
			return;
		}
		List<java.sql.Date> last = jdbc.queryForList("select max(candle_date) from price_candles where ticker = 'SPY'", java.sql.Date.class);
		if (last.isEmpty() || last.get(0) == null) {
			return;
		}
		for (MacroEventCalendar.Event e : calendar.between(TRACK_FROM, last.get(0).toLocalDate())) {
			record(e);
		}
		Map<String, Object> s = jdbc.queryForMap("select count(*) as n, coalesce(sum(return_pct), 0) as total from macro_short_shadow");
		int n = ((Number) s.get("n")).intValue();
		double total = ((Number) s.get("total")).doubleValue();
		String details = GateMath.json("releases", n, "needed", RELEASES, "totalPct", total);
		if (n >= RELEASES && total <= 0) {
			gates.record(gate(), Status.FAILED, details, String.format(java.util.Locale.ROOT,
					"dropped: %d releases, %+.2f%% total after costs", n, total));
		}
		else if (n >= RELEASES) {
			gates.record(gate(), Status.SHADOW, details, String.format(java.util.Locale.ROOT,
					"beat zero after %d releases (%+.2f%%) — review before it trades", n, total));
		}
		else {
			gates.record(gate(), Status.SHADOW, details, n + " of " + RELEASES + " releases tracked");
		}
	}

	private void record(MacroEventCalendar.Event e) {
		List<Map<String, Object>> px = jdbc.queryForList("""
				select (select close::float8 from price_candles where ticker = 'SPY' and candle_date = ?) as exit,
				       (select close::float8 from price_candles where ticker = 'SPY' and candle_date < ? order by candle_date desc limit 1) as entry,
				       (select candle_date from price_candles where ticker = 'SPY' and candle_date < ? order by candle_date desc limit 1) as entry_date""",
				e.date(), e.date(), e.date());
		Map<String, Object> m = px.get(0);
		if (m.get("exit") == null || m.get("entry") == null) {
			return; // market closed that day, or the bar hasn't arrived
		}
		double entry = ((Number) m.get("entry")).doubleValue();
		double exit = ((Number) m.get("exit")).doubleValue();
		double ret = -100 * (exit / entry - 1) - COST_PCT;
		int n = jdbc.update("""
				insert into macro_short_shadow (event_date, kind, entry_date, spy_entry, spy_exit, return_pct)
				values (?, ?, ?, ?, ?, ?) on conflict (event_date) do nothing""",
				e.date(), e.kind().name(), m.get("entry_date"), entry, exit, ret);
		if (n > 0) {
			log.info("Macro short shadow: {} {} → {}%", e.kind(), e.date(), String.format(java.util.Locale.ROOT, "%+.2f", ret));
		}
	}
}
