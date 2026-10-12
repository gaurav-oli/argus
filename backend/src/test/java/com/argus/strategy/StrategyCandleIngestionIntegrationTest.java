package com.argus.strategy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.argus.TestcontainersConfiguration;
import com.argus.marketdata.YahooChartClient;
import com.argus.technical.HistoryReadjusted;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/** Agent 15's ranking-universe candles: a top-up notices when Yahoo has re-based a ticker's history. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class StrategyCandleIngestionIntegrationTest {

	@Autowired
	JdbcTemplate jdbc;

	private final YahooChartClient yahoo = mock(YahooChartClient.class);
	private final StrategyUniverseService universe = mock(StrategyUniverseService.class);
	private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
	private StrategyCandleIngestion ingestion;

	private final LocalDate d1 = LocalDate.now().minusDays(14);
	private final LocalDate d2 = d1.plusDays(1);
	private final LocalDate d3 = d1.plusDays(2);

	@BeforeEach
	void setUp() {
		jdbc.update("delete from price_candles where ticker = 'ZZSPIN'");
		for (Object[] r : List.of(new Object[] {d1, "77.65"}, new Object[] {d2, "78.00"})) {
			jdbc.update("insert into price_candles (ticker, candle_date, open, high, low, close, volume) values ('ZZSPIN', ?, ?, ?, ?, ?, 1)",
					r[0], new BigDecimal((String) r[1]), new BigDecimal((String) r[1]), new BigDecimal((String) r[1]),
					new BigDecimal((String) r[1]));
		}
		when(universe.activeTickers()).thenReturn(List.of("ZZSPIN"));
		ingestion = new StrategyCandleIngestion(yahoo, jdbc, universe, 80, "10y", true, events);
		ingestion.setRequestSpacingMs(0);
	}

	private static YahooChartClient.Bar bar(LocalDate d, String close) {
		BigDecimal p = new BigDecimal(close);
		return new YahooChartClient.Bar(d, p, p, p, p, 1);
	}

	private static Optional<YahooChartClient.Series> series(YahooChartClient.Bar... bars) {
		return Optional.of(new YahooChartClient.Series("ZZSPIN", "USD", null, null, List.of(bars)));
	}

	private BigDecimal storedClose(LocalDate d) {
		return jdbc.queryForObject("select close from price_candles where ticker = 'ZZSPIN' and candle_date = ?", BigDecimal.class, d);
	}

	@Test
	void aSpinOffReBasedByYahooRebuildsTheHistory() {
		// CTVA-style: Yahoo now shows the pre-spin-off days ÷6.665.
		when(yahoo.fetch("ZZSPIN", "3mo")).thenReturn(series(bar(d1, "11.65"), bar(d2, "11.70"), bar(d3, "12.57")));
		when(yahoo.fetch("ZZSPIN", "10y")).thenReturn(series(bar(d1.minusDays(1), "11.60"), bar(d1, "11.65"), bar(d2, "11.70"),
				bar(d3, "12.57")));

		ingestion.ingestOnce();

		assertEquals(0, new BigDecimal("11.65").compareTo(storedClose(d1)), "the old 77.65 is replaced");
		assertEquals(4, jdbc.queryForObject("select count(*) from price_candles where ticker = 'ZZSPIN'", Integer.class));
		ArgumentCaptor<HistoryReadjusted> e = ArgumentCaptor.forClass(HistoryReadjusted.class);
		verify(events).publishEvent(e.capture());
		assertEquals(0.15, e.getValue().factor(), 0.001);
	}

	@Test
	void aMatchingTopUpOnlyAddsNewBars() {
		when(yahoo.fetch("ZZSPIN", "3mo")).thenReturn(series(bar(d1, "77.65"), bar(d2, "78.00"), bar(d3, "79.10")));

		ingestion.ingestOnce();

		assertEquals(0, new BigDecimal("77.65").compareTo(storedClose(d1)));
		assertEquals(0, new BigDecimal("79.10").compareTo(storedClose(d3)));
		verify(events, never()).publishEvent(any(Object.class));
	}
}
