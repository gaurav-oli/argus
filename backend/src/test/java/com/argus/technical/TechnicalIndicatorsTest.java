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
		// 20 candles: enough for RSI(15), SMA20, and Bollinger %B(20); not enough for SMA50 or MACD(35).
		double[] values = new double[20];
		for (int i = 0; i < 20; i++) {
			values[i] = 100 + i;
		}
		Optional<TechnicalIndicators.Snapshot> snap = TechnicalIndicators.snapshot(closes(values));

		assertTrue(snap.isPresent());
		assertTrue(snap.get().sma20() != null);
		assertNull(snap.get().sma50(), "50-period SMA must be null with only 20 candles");
		assertNull(snap.get().macdHistogram(), "MACD needs 35 candles, must be null with only 20");
		assertTrue(snap.get().bollingerPercentB() != null, "Bollinger %B only needs 20 candles");
		assertEquals(0, BigDecimal.valueOf(100).compareTo(snap.get().rsi14()), "all-gains window");
	}

	// ---- macdHistogram (ta4j-backed) ----

	@Test
	void macdHistogramIsExactlyZeroOnAFlatPriceSeries() {
		// A constant price, however EMA is seeded, stays at that same constant at every bar — so
		// MACD (fast EMA − slow EMA) is 0 at every bar, and the signal line (EMA of a constant-zero
		// series) is 0 too. Histogram = 0 − 0 = 0. True regardless of ta4j's internal seeding rule.
		double[] flat = new double[40];
		java.util.Arrays.fill(flat, 100.0);

		Optional<Double> histogram = TechnicalIndicators.macdHistogram(closes(flat));

		assertTrue(histogram.isPresent());
		assertEquals(0.0, histogram.get(), 0.0001);
	}

	@Test
	void macdHistogramIsPositiveDuringASustainedUptrend() {
		// Textbook MACD behavior: in a steady uptrend the fast EMA leads the slow EMA upward, and
		// the MACD line runs above its own lagging signal line — a positive histogram.
		double[] rising = new double[40];
		for (int i = 0; i < 40; i++) {
			rising[i] = 100 + i;
		}

		Optional<Double> histogram = TechnicalIndicators.macdHistogram(closes(rising));

		assertTrue(histogram.isPresent());
		assertTrue(histogram.get() > 0, "a sustained uptrend must read as positive MACD momentum");
	}

	@Test
	void macdHistogramEmptyBelowThirtyFiveCandles() {
		assertTrue(TechnicalIndicators.macdHistogram(closes(new double[34])).isEmpty());
	}

	// ---- bollingerPercentB (ta4j-backed) ----

	@Test
	void bollingerPercentBAboveOneOnABreakoutSpike() {
		// 19 flat candles at 100, then one sharp spike to 130 — the spike must read above the upper
		// band (%B > 1), a real breakout beyond the bands the flat history established.
		double[] values = new double[20];
		java.util.Arrays.fill(values, 100.0);
		values[19] = 130;

		Optional<Double> pctB = TechnicalIndicators.bollingerPercentB(closes(values));

		assertTrue(pctB.isPresent());
		assertTrue(pctB.get() > 1.0, "a sharp spike above 19 flat candles must breach the upper band");
	}

	@Test
	void bollingerPercentBWithinBandsForOrdinaryFluctuation() {
		// Oscillates between 98 and 102, ending at a middling value — normal noise, not a breakout,
		// so %B should stay within the 0..1 band range.
		double[] values = new double[20];
		for (int i = 0; i < 19; i++) {
			values[i] = i % 2 == 0 ? 98 : 102;
		}
		values[19] = 100;

		Optional<Double> pctB = TechnicalIndicators.bollingerPercentB(closes(values));

		assertTrue(pctB.isPresent());
		assertTrue(pctB.get() > 0.0 && pctB.get() < 1.0, "ordinary fluctuation must stay within the bands");
	}

	@Test
	void bollingerPercentBEmptyBelowTwentyCandles() {
		assertTrue(TechnicalIndicators.bollingerPercentB(closes(new double[19])).isEmpty());
	}

	@Test
	void aCandleWhoseLowIsAHairAboveItsOpenStillStudies() {
		// ASTS 2026-10-06 as the feed sent it: low 60.064 above open 60.030. The indicator library rejects such
		// a bar, which used to throw out of every chart-dependent agent for the ticker.
		java.util.List<PriceCandle> candles = new java.util.ArrayList<>();
		for (int i = 0; i < 40; i++) {
			java.math.BigDecimal p = java.math.BigDecimal.valueOf(60 + i * 0.1);
			candles.add(new PriceCandle("ASTS", java.time.LocalDate.of(2026, 8, 1).plusDays(i), p, p.add(java.math.BigDecimal.ONE), p.subtract(java.math.BigDecimal.ONE), p, 1000L));
		}
		PriceCandle odd = new PriceCandle("ASTS", java.time.LocalDate.of(2026, 9, 10), new java.math.BigDecimal("60.029999"),
				new java.math.BigDecimal("64.75"), new java.math.BigDecimal("60.063999"), new java.math.BigDecimal("63.119999"), 1000L);
		candles.add(odd);

		org.junit.jupiter.api.Assertions.assertEquals(0, new java.math.BigDecimal("60.029999").compareTo(odd.getLow()),
				"the low is widened to the open");
		org.junit.jupiter.api.Assertions.assertTrue(TechnicalIndicators.macdHistogram(candles).isPresent());
		org.junit.jupiter.api.Assertions.assertTrue(ChartReader.study(candles, java.util.List.of()).isPresent());
	}
}
