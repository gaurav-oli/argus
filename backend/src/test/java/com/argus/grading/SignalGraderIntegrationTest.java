package com.argus.grading;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.argus.TestcontainersConfiguration;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/** The grader keeps one prediction per agent per stock per day and scores it against SPY over trading days. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class SignalGraderIntegrationTest {

	private static final String T = "ZZGRADE";
	private static final String SPY = "SPY";

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	SignalGrader grader;

	private final List<LocalDate> days = new ArrayList<>();

	@BeforeEach
	void setUp() {
		jdbc.update("delete from signal_grade where ticker = ?", T);
		jdbc.update("delete from rec_grade where ticker = ?", T);
		jdbc.update("delete from recommendation_signals where recommendation_id in (select id from recommendations where ticker = ?)", T);
		jdbc.update("delete from recommendations where ticker = ?", T);
		LocalDate d = LocalDate.of(2026, 10, 1);
		while (days.size() < 25) {
			if (d.getDayOfWeek() != DayOfWeek.SATURDAY && d.getDayOfWeek() != DayOfWeek.SUNDAY) days.add(d);
			d = d.plusDays(1);
		}
		jdbc.update("delete from price_candles where ticker in (?, ?) and candle_date between ? and ?", T, SPY, days.get(0),
				days.get(days.size() - 1));
		for (int i = 0; i < days.size(); i++) {
			candle(SPY, days.get(i), 500.0); // flat market
			candle(T, days.get(i), 100.0 * Math.pow(1.01, i)); // +1% a day
		}
	}

	private void candle(String ticker, LocalDate d, double close) {
		BigDecimal c = BigDecimal.valueOf(close);
		jdbc.update("""
				insert into price_candles (ticker, candle_date, open, high, low, close, volume) values (?, ?, ?, ?, ?, ?, 1)
				on conflict (ticker, candle_date) do update set close = excluded.close""", ticker, d, c, c, c, c);
	}

	private long rec(LocalDate day, int hour, String action, Object[]... signals) {
		Timestamp at = Timestamp.from(day.atTime(hour, 0).atZone(ZoneId.of("America/New_York")).toInstant());
		Long id = jdbc.queryForObject("""
				insert into recommendations (ticker, direction, bull_probability, bear_probability, confidence, created_at,
				                             action, conviction_score, sector, features)
				values (?, 'BULLISH', 0.6, 0.4, 0.5, ?, ?, 65, 'TECHNOLOGY', '["regime=RISK_OFF","earnings=soon"]')
				returning id""", Long.class, T, at, action);
		for (Object[] s : signals) {
			jdbc.update("""
					insert into recommendation_signals (recommendation_id, agent, direction, weight, signed_weight)
					values (?, ?, ?, ?, ?)""", id, s[0], s[1], s[2], s[2]);
		}
		return id;
	}

	@Test
	void gradesTheLastWordOfTheDayAgainstSpy() {
		rec(days.get(0), 10, "WATCH", new Object[] {"agent-1-news", "BULLISH", 0.5}, new Object[] {"agent-8-macro", "BEARISH", 0.4});
		rec(days.get(0), 14, "BUY", new Object[] {"agent-1-news", "BULLISH", 0.7});

		grader.recordPredictions(days.get(0));
		grader.recordCalls(days.get(0));
		grader.fillEntries("signal_grade");
		grader.fillEntries("rec_grade");
		for (int h : SignalGrader.HORIZONS) {
			grader.fillExcess("signal_grade", h);
			grader.fillExcess("rec_grade", h);
		}

		List<Map<String, Object>> rows = jdbc.queryForList(
				"select agent, strength, regime, earnings_week, entry_close, excess_5, excess_20, excess_60 from signal_grade where ticker = ? order by agent", T);
		assertEquals(2, rows.size()); // one prediction per agent per day, not one per review
		Map<String, Object> news = rows.get(0);
		assertEquals("agent-1-news", news.get("agent"));
		assertEquals(0.7, (Double) news.get("strength"), 1e-9); // the day's last word
		assertEquals("RISK_OFF", news.get("regime"));
		assertEquals(true, news.get("earnings_week"));
		assertEquals(100.0, (Double) news.get("entry_close"), 1e-6);
		assertEquals(100 * (Math.pow(1.01, 5) - 1), (Double) news.get("excess_5"), 1e-4);
		assertEquals(100 * (Math.pow(1.01, 20) - 1), (Double) news.get("excess_20"), 1e-4);
		assertNull(news.get("excess_60")); // that window hasn't closed

		Map<String, Object> call = jdbc.queryForMap("select action, actionable_bar, excess_20 from rec_grade where ticker = ?", T);
		assertEquals("BUY", call.get("action"));
		assertEquals(true, call.get("actionable_bar"));
		assertEquals(100 * (Math.pow(1.01, 20) - 1), (Double) call.get("excess_20"), 1e-4);
	}
}
