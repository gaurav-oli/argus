package com.argus.grading;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.argus.TestcontainersConfiguration;
import com.argus.recommendation.ShadowBook;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/** A shadow trade that gaps through its stop fills at the open, and its post-mortem calls it bad luck. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class ShadowBookPostmortemIntegrationTest {

	private static final String T = "ZZSHADOW";

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	ShadowBook shadowBook;

	@Autowired
	PostmortemService postmortems;

	private final LocalDate d0 = LocalDate.of(2026, 10, 5); // a Monday

	@BeforeEach
	void setUp() {
		jdbc.update("delete from trade_postmortem where ticker = ?", T);
		jdbc.update("delete from shadow_trades where ticker = ?", T);
		jdbc.update("delete from price_candles where ticker = ? ", T);
		for (int i = 0; i < 3; i++) {
			bar("SPY", d0.plusDays(i), 500, 500, 500, 500);
		}
		bar(T, d0, 100, 101, 99, 100);
		bar(T, d0.plusDays(1), 99.5, 100, 98, 99);
		bar(T, d0.plusDays(2), 93, 94, 92, 93.5); // opens through the 95 stop
		jdbc.update("""
				insert into shadow_trades (ticker, direction, sector, entry_at, entry_date, entry_price, spy_entry, stop_price,
				    initial_stop, target_price, high_water, atr_pct, cost_pct, hold_days)
				values (?, 'BULLISH', 'TECHNOLOGY', ?, ?, 100, 500, 95, 95, 110, 100, 1.5, 0.2, 30)""",
				T, Timestamp.from(d0.atTime(15, 0).atZone(ZoneId.of("America/New_York")).toInstant()), d0);
	}

	private void bar(String t, LocalDate d, double o, double h, double l, double c) {
		jdbc.update("""
				insert into price_candles (ticker, candle_date, open, high, low, close, volume) values (?, ?, ?, ?, ?, ?, 1)
				on conflict (ticker, candle_date) do update set open = excluded.open, high = excluded.high,
				    low = excluded.low, close = excluded.close""",
				t, d, BigDecimal.valueOf(o), BigDecimal.valueOf(h), BigDecimal.valueOf(l), BigDecimal.valueOf(c));
	}

	@Test
	void gapThroughStopFillsAtOpenAndIsBadLuck() {
		shadowBook.run();
		Map<String, Object> t = jdbc.queryForMap(
				"select status, exit_price, exit_reason, return_pct, excess_return_pct from shadow_trades where ticker = ?", T);
		assertEquals("CLOSED", t.get("status"));
		assertEquals(93.0, (Double) t.get("exit_price"), 1e-9); // the gapped open, not the 95 stop
		assertEquals("STOP", t.get("exit_reason"));
		assertEquals(-7.2, (Double) t.get("return_pct"), 1e-9); // −7% less the 0.2% round-trip cost
		assertEquals(-7.2, (Double) t.get("excess_return_pct"), 1e-9);

		postmortems.run();
		Map<String, Object> p = jdbc.queryForMap(
				"select bucket, shock, shock_reason, final, hold_excess_pct from trade_postmortem where ticker = ?", T);
		assertEquals("BAD_LUCK", p.get("bucket"));
		assertEquals(true, p.get("shock"));
		assertTrue(((String) p.get("shock_reason")).startsWith("gapped"));
		assertEquals(false, p.get("final")); // the 30-day hold window hasn't closed yet
	}
}
