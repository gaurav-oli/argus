package com.argus.strategy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.argus.TestcontainersConfiguration;
import java.sql.Date;
import java.time.LocalDate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class StrategySandboxIntegrationTest {

	@Autowired
	StrategySandboxService sandbox;

	@Autowired
	JdbcTemplate jdbc;

	private static final LocalDate D0 = LocalDate.of(2030, 1, 2);
	private static final LocalDate D30 = D0.plusDays(30);

	@BeforeEach
	void clean() {
		jdbc.update("delete from academic_strategy where acronym like 'TST%'"); // cascades to sandbox, calls, scores
		jdbc.update("delete from price_candles where candle_date >= ?", Date.valueOf(D0));
		jdbc.update("insert into academic_strategy (acronym, name, kind, sign) values ('TSTMOM', 'Test momentum', 'PREDICTOR', 1)");
	}

	private void candle(String ticker, LocalDate d, double close) {
		jdbc.update("insert into price_candles (ticker, candle_date, open, high, low, close) values (?, ?, ?, ?, ?, ?)", ticker,
				Date.valueOf(d), close, close, close, close);
	}

	/** {@code n} tickers with a strong bullish reading on D0; {@code winners} of them beat SPY by D30. */
	private void universe(int n, int winners) {
		candle("SPY", D0, 500);
		candle("SPY", D30, 505); // +1%
		for (int i = 0; i < n; i++) {
			String t = "TS%02d".formatted(i);
			jdbc.update("insert into strategy_score (acronym, ticker, as_of, percentile) values ('TSTMOM', ?, ?, 0.95)", t, Date.valueOf(D0));
			candle(t, D0, 100);
			candle(t, D30, i < winners ? 110 : 95);
		}
	}

	@Test
	void aStrategyShadowsFirstAndIsPromotedOnlyAfterItsCallsBeatSpy() {
		universe(30, 18);
		assertTrue(sandbox.enroll("TSTMOM", 30));
		assertFalse(sandbox.enroll("TSTMOM", 30), "already in the sandbox");

		StrategySandboxService.Pass first = sandbox.pass(D0.plusDays(1));
		assertEquals(30, first.made());
		assertEquals(0, first.resolved());
		assertFalse(sandbox.liveAcronyms().contains("TSTMOM"), "shadow calls have no live effect");

		StrategySandboxService.Pass due = sandbox.pass(D30.plusDays(1));
		assertEquals(30, due.resolved());
		assertEquals("PROMOTED", jdbc.queryForObject("select state from strategy_sandbox where acronym = 'TSTMOM'", String.class));
		assertTrue(sandbox.liveAcronyms().contains("TSTMOM"));
		StrategySandboxService.SandboxView v = sandbox.list().stream().filter(x -> x.acronym().equals("TSTMOM")).findFirst().orElseThrow();
		assertEquals(60, v.hitPct());
		assertEquals(0, v.meanExcessPct().compareTo(new java.math.BigDecimal("3.0000")), "(18×9 − 12×6) / 30");
		assertTrue(v.live());
	}

	@Test
	void aStrategyWhoseCallsLoseIsKilled() {
		universe(30, 5);
		sandbox.enroll("TSTMOM", 30);
		sandbox.pass(D0.plusDays(1));
		sandbox.pass(D30.plusDays(1));
		assertEquals("KILLED", jdbc.queryForObject("select state from strategy_sandbox where acronym = 'TSTMOM'", String.class));
		assertFalse(sandbox.liveAcronyms().contains("TSTMOM"));
	}

	@Test
	void weakViewsMakeNoCallsAndStrongBearishViewsShort() {
		candle("SPY", D0, 500);
		candle("TSA", D0, 10);
		candle("TSB", D0, 10);
		jdbc.update("insert into strategy_score (acronym, ticker, as_of, percentile) values ('TSTMOM', 'TSA', ?, 0.6)", Date.valueOf(D0));
		jdbc.update("insert into strategy_score (acronym, ticker, as_of, percentile) values ('TSTMOM', 'TSB', ?, 0.03)", Date.valueOf(D0));
		sandbox.enroll("TSTMOM", 30);

		assertEquals(1, sandbox.pass(D0.plusDays(1)).made());
		assertEquals("BEARISH", jdbc.queryForObject("select direction from strategy_shadow_call where ticker = 'TSB'", String.class));
		assertEquals(0, sandbox.pass(D0.plusDays(2)).made(), "one open call per ticker at a time");
	}
}
