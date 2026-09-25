package com.argus.regime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.argus.marketdata.YahooChartClient;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MarketRegimeTest {

	private static final LocalDate TODAY = LocalDate.of(2026, 9, 24);

	private static YahooChartClient.Series series(double prevClose, double live) {
		YahooChartClient.Bar prev = new YahooChartClient.Bar(TODAY.minusDays(1), BigDecimal.ONE, BigDecimal.ONE,
				BigDecimal.ONE, BigDecimal.valueOf(prevClose), 1);
		return new YahooChartClient.Series("X", "USD", BigDecimal.valueOf(live), TODAY, List.of(prev));
	}

	@Test
	void aBroadSelloffAndRiskOffAreDetected() {
		MarketRegime r = new MarketRegime(Instant.now(), -1.8, -2.9, 28.0, 15.0, 1.0, Map.of());
		assertTrue(r.broadSelloff());
		assertTrue(r.riskOff());
		assertEquals("RISK_OFF", r.label());
		assertTrue(r.summary().contains("broad selloff"));
	}

	@Test
	void aQuietTapeIsNotFlagged() {
		MarketRegime r = new MarketRegime(Instant.now(), 0.7, 1.0, 14.0, -3.0, 0.2, Map.of());
		assertFalse(r.broadSelloff());
		assertFalse(r.riskOff());
		assertTrue(r.riskOn());
		assertEquals("RISK_ON", r.label());
	}

	@Test
	void risingYieldsAreDetected() {
		assertTrue(new MarketRegime(Instant.now(), 0.0, 0.0, 18.0, 0.0, 5.0, Map.of()).ratesRising());
	}

	@Test
	void unavailableDataMeansNoGuardFires() {
		MarketRegime r = MarketRegime.unavailable();
		assertFalse(r.available());
		assertFalse(r.broadSelloff());
		assertFalse(r.riskOff());
		assertFalse(r.riskOn());
		assertEquals("UNKNOWN", r.label());
	}

	@Test
	void buildComputesTodaysMovesFromTheFetchedSeries() {
		MarketRegime r = MarketRegimeService.build(Map.of(
				"SPY", series(100, 98),
				"QQQ", series(100, 97),
				"^VIX", series(20, 26),
				"XLE", series(50, 51)));

		assertEquals(-2.0, r.spy1d(), 1e-6);
		assertEquals(-3.0, r.qqq1d(), 1e-6);
		assertEquals(26.0, r.vix(), 1e-6);
		assertEquals(30.0, r.vix1d(), 1e-6);
		assertEquals(2.0, r.sectorMove(Sector.ENERGY), 1e-6);
		assertTrue(r.broadSelloff());
	}

	@Test
	void buildWithoutSpyIsUnavailable() {
		assertFalse(MarketRegimeService.build(Map.of("QQQ", series(100, 99))).available());
	}
}
