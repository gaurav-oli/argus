package com.argus.learning;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.argus.TestcontainersConfiguration;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class PatternLibraryIntegrationTest {

	@Autowired
	PatternLibraryService library;

	@Autowired
	JdbcTemplate jdbc;

	private static final String FP = "[\"dir=BULLISH\",\"lead=NEWS\",\"regime=RISK_OFF\",\"sector=TECH\",\"trend=DOWNTREND\"]";

	@BeforeEach
	void clean() {
		jdbc.update("delete from pattern_check");
		jdbc.update("delete from simulated_trades");
	}

	private void closedTrade(boolean won, String exit, String fingerprint) {
		jdbc.update("""
				insert into simulated_trades (ticker, direction, notional, entry_price, shares, entry_at, horizon_days, status,
				    exit_price, return_pct, won, closed_at, exit_reason, setup_fingerprint)
				values ('ZZZ', 'BULLISH', 100, 10, 10, now() - interval '40 days', 30, 'CLOSED', 9, -10, ?, now() - interval '5 days',
				    ?, ?)
				""", won, exit, fingerprint);
	}

	@Test
	void anEmptyLibraryProceedsAndTheCheckIsLogged() {
		PatternAdvice a = library.consult(1L, "AAPL", "BULLISH", Set.of("dir=BULLISH", "lead=NEWS"));
		assertEquals("NO_PATTERN", a.action());
		assertEquals(1, jdbc.queryForObject("select count(*) from pattern_check where action = 'NO_PATTERN'", Integer.class));
	}

	@Test
	void similarLosersAreFoundAndTheSkipIsLogged() {
		for (int i = 0; i < 8; i++) closedTrade(false, "STOP", FP);
		closedTrade(true, "HORIZON", FP);
		closedTrade(false, "STOP", "[\"dir=BEARISH\",\"lead=NEWS\"]"); // other direction: ignored by the query anyway

		PatternAdvice a = library.consult(2L, "AAPL", "BULLISH",
				Set.of("dir=BULLISH", "lead=NEWS", "regime=RISK_OFF", "sector=TECH", "trend=DOWNTREND", "hold=30"));

		assertEquals("SKIP", a.action());
		assertEquals(9, a.matches());
		assertEquals("SKIP", jdbc.queryForObject("select action from pattern_check where recommendation_id = 2", String.class));
		assertEquals(1, library.recent("aapl", 10).size());
		assertEquals(1L, library.actionCounts(30).get("SKIP"));
	}
}
