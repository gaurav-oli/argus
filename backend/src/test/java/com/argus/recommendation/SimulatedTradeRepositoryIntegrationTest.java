package com.argus.recommendation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.argus.TestcontainersConfiguration;
import java.math.BigDecimal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/** The thesis dedup only sees legs the current recommendation system opened. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class SimulatedTradeRepositoryIntegrationTest {

	@Autowired
	SimulatedTradeRepository trades;

	@Autowired
	JdbcTemplate jdbc;

	@BeforeEach
	void clean() {
		jdbc.update("delete from simulated_trades");
	}

	private SimulatedTrade openLeg(String enteredAt) {
		SimulatedTrade t = trades.save(new SimulatedTrade(null, "AAPL", SignalDirection.BULLISH, new BigDecimal("100"),
				new BigDecimal("200"), 30, null));
		jdbc.update("update simulated_trades set entry_at = ?::timestamptz where id = ?", enteredAt, t.getId());
		return t;
	}

	@Test
	void anOldSystemLegDoesNotBlockANewSystemLegOnTheSameThesis() {
		openLeg("2026-09-14T18:00:00Z"); // the old coin-flip system

		assertFalse(trades.existsByTickerAndDirectionAndHorizonDaysAndStatusAndEntryAtGreaterThanEqual("AAPL",
				SignalDirection.BULLISH, 30, SimulatedTrade.Status.OPEN, PaperInvestorService.NEW_SYSTEM_SINCE));
		assertTrue(trades.findByTickerAndDirectionAndStatusAndEntryAtGreaterThanEqual("AAPL", SignalDirection.BULLISH,
				SimulatedTrade.Status.OPEN, PaperInvestorService.NEW_SYSTEM_SINCE).isEmpty());
	}

	@Test
	void aNewSystemLegStillBlocksADuplicate() {
		openLeg("2026-10-07T00:00:00Z");

		assertTrue(trades.existsByTickerAndDirectionAndHorizonDaysAndStatusAndEntryAtGreaterThanEqual("AAPL",
				SignalDirection.BULLISH, 30, SimulatedTrade.Status.OPEN, PaperInvestorService.NEW_SYSTEM_SINCE));
		assertEquals(1, trades.findByTickerAndDirectionAndStatusAndEntryAtGreaterThanEqual("AAPL", SignalDirection.BULLISH,
				SimulatedTrade.Status.OPEN, PaperInvestorService.NEW_SYSTEM_SINCE).size());
	}

	@Test
	void eraStatsSplitTheBookByEntryDate() {
		SimulatedTrade old = openLeg("2026-09-14T18:00:00Z");
		openLeg("2026-10-07T00:00:00Z");
		jdbc.update("update simulated_trades set status = 'CLOSED', won = true, return_pct = 4.5 where id = ?", old.getId());

		Object[] before = trades.eraStats(java.time.Instant.EPOCH, PaperInvestorService.NEW_SYSTEM_SINCE).get(0);
		Object[] after = trades.eraStats(PaperInvestorService.NEW_SYSTEM_SINCE, java.time.Instant.now().plusSeconds(86_400)).get(0);

		assertEquals(1L, ((Number) before[0]).longValue(), "one closed old-system leg");
		assertEquals(1L, ((Number) before[1]).longValue(), "and it won");
		assertEquals(0, new BigDecimal("4.5").compareTo(new BigDecimal(before[2].toString())));
		assertEquals(0L, ((Number) after[0]).longValue(), "the current system has nothing closed yet");
		assertEquals(1L, ((Number) after[3]).longValue(), "but one leg open");
	}
}
