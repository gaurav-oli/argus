package com.argus.technical;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Pure indicator math (Agent 10 — Technical Analysis) against hand-computed sequences. */
class TechnicalIndicatorsTest {

	private static PriceCandle candle(LocalDate date, double high, double close) {
		return new PriceCandle("AAPL", date, BigDecimal.valueOf(close), BigDecimal.valueOf(high),
				BigDecimal.valueOf(close), BigDecimal.valueOf(close), 1000L);
	}

	private static List<PriceCandle> closes(double... values) {
		List<PriceCandle> out = new ArrayList<>();
		LocalDate start = LocalDate.of(2026, 1, 1);
		for (int i = 0; i < values.length; i++) {
			out.add(candle(start.plusDays(i), values[i], values[i]));
		}
		return out;
	}

	// ---- sma ----

	@Test
	void smaAveragesExactlyThePeriodWindow() {
		double[] values = new double[20];
		for (int i = 0; i < 20; i++) {
			values[i] = i + 1; // 1..20
		}
		Optional<BigDecimal> sma = TechnicalIndicators.sma(closes(values), 20);

		assertTrue(sma.isPresent());
		assertEquals(0, BigDecimal.valueOf(10.5).compareTo(sma.get()));
	}

	@Test
	void smaEmptyWhenNotEnoughHistory() {
		assertTrue(TechnicalIndicators.sma(closes(1, 2, 3), 20).isEmpty());
	}

	// ---- rsi14 ----

	@Test
	void rsiIsOneHundredWhenEveryChangeIsAGain() {
		double[] values = new double[15];
		for (int i = 0; i < 15; i++) {
			values[i] = 10 + i; // strictly increasing, 14 gains
		}
		Optional<BigDecimal> rsi = TechnicalIndicators.rsi14(closes(values));

		assertTrue(rsi.isPresent());
		assertEquals(0, BigDecimal.valueOf(100).compareTo(rsi.get()));
	}

	@Test
	void rsiIsZeroWhenEveryChangeIsALoss() {
		double[] values = new double[15];
		for (int i = 0; i < 15; i++) {
			values[i] = 24 - i; // strictly decreasing, 14 losses
		}
		Optional<BigDecimal> rsi = TechnicalIndicators.rsi14(closes(values));

		assertTrue(rsi.isPresent());
		assertEquals(0, BigDecimal.ZERO.compareTo(rsi.get()));
	}

	@Test
	void rsiMixedSequenceMatchesHandComputedValue() {
		// Alternating +2/-1, 7 of each (14 changes): avgGain=14/14=1.0, avgLoss=7/14=0.5,
		// RS=2.0, RSI=100-100/3=66.666...67 (setScale(2, HALF_UP)).
		double[] values = {100, 102, 101, 103, 102, 104, 103, 105, 104, 106, 105, 107, 106, 108, 107};
		Optional<BigDecimal> rsi = TechnicalIndicators.rsi14(closes(values));

		assertTrue(rsi.isPresent());
		assertEquals(0, new BigDecimal("66.67").compareTo(rsi.get()));
	}

	@Test
	void rsiEmptyWhenNotEnoughHistory() {
		double[] values = new double[10]; // needs 15
		assertTrue(TechnicalIndicators.rsi14(closes(values)).isEmpty());
	}

	// ---- drawdownFromHighPct ----

	@Test
	void drawdownComputesPercentBelowTheWindowHigh() {
		List<PriceCandle> candles = List.of(
				candle(LocalDate.of(2026, 1, 1), 100, 100),
				candle(LocalDate.of(2026, 1, 2), 110, 110), // the window high
				candle(LocalDate.of(2026, 1, 3), 105, 105),
				candle(LocalDate.of(2026, 1, 4), 90, 88));  // last close = 88

		Optional<Double> drawdown = TechnicalIndicators.drawdownFromHighPct(candles, 4);

		assertTrue(drawdown.isPresent());
		assertEquals(-20.0, drawdown.get(), 0.001, "(88-110)/110*100 = -20%");
	}

	@Test
	void drawdownIsZeroAtANewHigh() {
		List<PriceCandle> candles = List.of(
				candle(LocalDate.of(2026, 1, 1), 100, 100),
				candle(LocalDate.of(2026, 1, 2), 105, 105)); // today's close IS the window high

		Optional<Double> drawdown = TechnicalIndicators.drawdownFromHighPct(candles, 2);

		assertTrue(drawdown.isPresent());
		assertEquals(0.0, drawdown.get(), 0.001);
	}

	@Test
	void drawdownEmptyWhenNotEnoughHistory() {
		assertTrue(TechnicalIndicators.drawdownFromHighPct(closes(100), 10).isEmpty());
	}

	// ---- snapshot ----

	@Test
	void snapshotEmptyBelowRsiFloor() {
		assertTrue(TechnicalIndicators.snapshot(closes(new double[10])).isEmpty());
	}

	@Test
	void snapshotPresentWithPartialSmasWhenShortOnHistory() {
		// 20 candles: enough for RSI(15) and SMA20, not enough for SMA50.
		double[] values = new double[20];
		for (int i = 0; i < 20; i++) {
			values[i] = 100 + i;
		}
		Optional<TechnicalIndicators.Snapshot> snap = TechnicalIndicators.snapshot(closes(values));

		assertTrue(snap.isPresent());
		assertTrue(snap.get().sma20() != null);
		assertNull(snap.get().sma50(), "50-period SMA must be null with only 20 candles");
		assertEquals(0, BigDecimal.valueOf(100).compareTo(snap.get().rsi14()), "all-gains window");
	}
}
