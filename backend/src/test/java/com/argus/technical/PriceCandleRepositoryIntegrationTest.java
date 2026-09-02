package com.argus.technical;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.argus.TestcontainersConfiguration;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * {@link PriceCandle}/{@link PriceCandleRepository} against real Postgres. {@link
 * CandleIngestionService} itself is only conditionally created when a Finnhub key is configured
 * (absent in the test profile) and is covered with mocked-{@code FinnhubRest} unit tests instead —
 * what those can't verify is that the real unique constraint and repository queries behave as the
 * ingestion/indicator code assumes.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class PriceCandleRepositoryIntegrationTest {

	@Autowired
	PriceCandleRepository candles;

	@BeforeEach
	void clean() {
		candles.deleteAll();
	}

	private static PriceCandle candle(String ticker, LocalDate date, double close) {
		return new PriceCandle(ticker, date, BigDecimal.valueOf(close), BigDecimal.valueOf(close + 1),
				BigDecimal.valueOf(close - 1), BigDecimal.valueOf(close), 1_000_000L);
	}

	@Test
	void savingTheSameTickerAndDateTwiceViolatesTheUniqueConstraint() {
		candles.saveAndFlush(candle("AAPL", LocalDate.of(2026, 1, 1), 100));

		assertThrows(DataIntegrityViolationException.class,
				() -> candles.saveAndFlush(candle("AAPL", LocalDate.of(2026, 1, 1), 101)));
	}

	@Test
	void existsByTickerAndCandleDateReflectsRealState() {
		assertFalse(candles.existsByTickerAndCandleDate("AAPL", LocalDate.of(2026, 1, 1)));

		candles.save(candle("AAPL", LocalDate.of(2026, 1, 1), 100));

		assertTrue(candles.existsByTickerAndCandleDate("AAPL", LocalDate.of(2026, 1, 1)));
		assertFalse(candles.existsByTickerAndCandleDate("AAPL", LocalDate.of(2026, 1, 2)),
				"a different date for the same ticker must not match");
	}

	@Test
	void existsByTickerIsUsedToDecideBackfillVsIncrementalIngest() {
		assertFalse(candles.existsByTicker("MSFT"));

		candles.save(candle("MSFT", LocalDate.of(2026, 1, 1), 200));

		assertTrue(candles.existsByTicker("MSFT"));
		assertFalse(candles.existsByTicker("GOOG"), "a different ticker must not be affected");
	}

	@Test
	void mostRecentCandlesComeBackNewestFirst() {
		candles.save(candle("AAPL", LocalDate.of(2026, 1, 1), 100));
		candles.save(candle("AAPL", LocalDate.of(2026, 1, 3), 102));
		candles.save(candle("AAPL", LocalDate.of(2026, 1, 2), 101));

		List<PriceCandle> recent = candles.findTop200ByTickerOrderByCandleDateDesc("AAPL");

		assertEquals(3, recent.size());
		assertEquals(LocalDate.of(2026, 1, 3), recent.get(0).getCandleDate());
		assertEquals(LocalDate.of(2026, 1, 1), recent.get(2).getCandleDate());
	}

	@Test
	void latestIngestedAtReflectsTheMostRecentSave() {
		candles.save(candle("AAPL", LocalDate.of(2026, 1, 1), 100));

		assertTrue(candles.latestIngestedAt() != null);
	}
}
