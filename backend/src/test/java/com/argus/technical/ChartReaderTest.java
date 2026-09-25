package com.argus.technical;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The chart study reads trend, volume, levels, candlestick patterns and relative strength from candles. */
class ChartReaderTest {

	private static final LocalDate START = LocalDate.of(2026, 1, 1);

	/** Builds candles from parallel arrays, one per consecutive day. */
	private static List<PriceCandle> candles(double[] o, double[] h, double[] l, double[] c, long[] v) {
		List<PriceCandle> out = new ArrayList<>();
		for (int i = 0; i < c.length; i++) {
			out.add(new PriceCandle("T", START.plusDays(i), bd(o[i]), bd(h[i]), bd(l[i]), bd(c[i]), v == null ? null : v[i]));
		}
		return out;
	}

	private static BigDecimal bd(double d) {
		return BigDecimal.valueOf(d);
	}

	/** Closes following {@code closes}; open = prior close; tiny wicks; constant volume. */
	private static List<PriceCandle> fromCloses(double[] closes) {
		int n = closes.length;
		double[] o = new double[n], h = new double[n], l = new double[n];
		long[] v = new long[n];
		for (int i = 0; i < n; i++) {
			o[i] = i == 0 ? closes[0] : closes[i - 1];
			h[i] = Math.max(o[i], closes[i]) * 1.002;
			l[i] = Math.min(o[i], closes[i]) * 0.998;
			v[i] = 1_000_000;
		}
		return candles(o, h, l, closes, v);
	}

	private static double[] ramp(double from, double to, int n) {
		double[] a = new double[n];
		for (int i = 0; i < n; i++) a[i] = from + (to - from) * i / (n - 1);
		return a;
	}

	private static double[] concat(double[]... parts) {
		int len = 0;
		for (double[] p : parts) len += p.length;
		double[] out = new double[len];
		int k = 0;
		for (double[] p : parts) for (double d : p) out[k++] = d;
		return out;
	}

	/** A 60-bar steady decline from 120 to 100, ready to append a reversal candle to. */
	private static double[] decline() {
		return concat(ramp(120, 120, 20), ramp(120, 100, 40));
	}

	private static ChartStudy study(List<PriceCandle> c) {
		return ChartReader.study(c, List.of()).orElseThrow();
	}

	@Test
	void tooLittleHistoryIsNotStudied() {
		assertTrue(ChartReader.study(fromCloses(ramp(100, 110, 20)), List.of()).isEmpty());
	}

	@Test
	void aSteadyUptrendIsReadAsAnUptrendAboveAllAverages() {
		ChartStudy s = study(fromCloses(ramp(50, 150, 260)));

		assertEquals(ChartStudy.Trend.UPTREND, s.trend());
		assertNotNull(s.sma200(), "260 bars is enough for a 200-day average");
		assertTrue(s.lastClose() > s.sma50() && s.sma50() > s.sma200());
		assertTrue(s.score() >= 0.25, "score " + s.score());
		assertEquals("BULLISH", s.bias());
	}

	@Test
	void aSteadyDowntrendIsBearish() {
		ChartStudy s = study(fromCloses(ramp(150, 50, 260)));

		assertEquals(ChartStudy.Trend.DOWNTREND, s.trend());
		assertTrue(s.score() <= -0.25, "score " + s.score());
		assertEquals("BEARISH", s.bias());
	}

	@Test
	void withoutTwoHundredBarsThereIsNoSma200ButTheTrendStillReads() {
		ChartStudy s = study(fromCloses(ramp(80, 120, 90)));

		assertNull(s.sma200());
		assertEquals(ChartStudy.Trend.UPTREND, s.trend());
	}

	// ---- candlestick patterns ----

	private static ChartStudy withFinalCandle(double[] priorCloses, double o, double h, double l, double c) {
		List<PriceCandle> base = new ArrayList<>(fromCloses(priorCloses));
		base.add(new PriceCandle("T", START.plusDays(priorCloses.length), bd(o), bd(h), bd(l), bd(c), 1_000_000L));
		return study(base);
	}

	private static boolean has(ChartStudy s, String name, String bias) {
		return s.patterns().stream().anyMatch(p -> p.name().equals(name) && p.bias().equals(bias));
	}

	@Test
	void aHammerAfterADeclineIsABullishReversal() {
		// Long lower shadow, small body near the high, right after a slide.
		ChartStudy s = withFinalCandle(decline(), 100.0, 100.8, 95.0, 100.6);

		assertTrue(has(s, "Hammer", "BULLISH"), s.patterns().toString());
	}

	@Test
	void theSameShapeInAnUptrendIsNotCalledAHammer() {
		ChartStudy s = withFinalCandle(ramp(80, 120, 60), 120.0, 120.8, 115.0, 120.6);

		assertTrue(s.patterns().stream().noneMatch(p -> p.name().equals("Hammer")), "a hammer needs a prior decline");
	}

	@Test
	void aBullishEngulfingAfterADeclineIsDetected() {
		double[] prior = decline();
		List<PriceCandle> base = new ArrayList<>(fromCloses(prior));
		int n = prior.length;
		double last = prior[n - 1];
		// A down candle, then an up candle that swallows it.
		base.add(new PriceCandle("T", START.plusDays(n), bd(last), bd(last), bd(last - 2.2), bd(last - 2.0), 1_000_000L));
		base.add(new PriceCandle("T", START.plusDays(n + 1), bd(last - 2.4), bd(last + 1.5), bd(last - 2.5), bd(last + 1.2), 1_500_000L));
		ChartStudy s = study(base);

		assertTrue(has(s, "Bullish engulfing", "BULLISH"), s.patterns().toString());
	}

	@Test
	void aShootingStarAfterARallyIsABearishReversal() {
		double[] rally = concat(ramp(100, 100, 20), ramp(100, 120, 40));
		ChartStudy s = withFinalCandle(rally, 120.2, 126.0, 119.9, 120.0);

		assertTrue(has(s, "Shooting star", "BEARISH"), s.patterns().toString());
	}

	@Test
	void aDojiAfterADeclineHintsAtABottom() {
		ChartStudy s = withFinalCandle(decline(), 100.0, 101.5, 98.5, 100.05);

		assertTrue(has(s, "Doji", "BULLISH"), s.patterns().toString());
	}

	@Test
	void patternsOlderThanFiveSessionsAreIgnored() {
		double[] prior = decline();
		List<PriceCandle> base = new ArrayList<>(fromCloses(prior));
		int n = prior.length;
		base.add(new PriceCandle("T", START.plusDays(n), bd(100.0), bd(100.8), bd(95.0), bd(100.6), 1_000_000L)); // hammer
		double[] after = ramp(100.6, 103, 8);
		for (int i = 0; i < after.length; i++) {
			double o = i == 0 ? 100.6 : after[i - 1];
			base.add(new PriceCandle("T", START.plusDays(n + 1 + i), bd(o), bd(Math.max(o, after[i]) * 1.001),
					bd(Math.min(o, after[i]) * 0.999), bd(after[i]), 1_000_000L));
		}

		assertTrue(study(base).patterns().stream().noneMatch(p -> p.name().equals("Hammer")));
	}

	// ---- volume ----

	@Test
	void heavierVolumeOnUpDaysReadsAsAccumulation() {
		double[] closes = new double[80];
		long[] vol = new long[80];
		double p = 100;
		for (int i = 0; i < 80; i++) {
			boolean up = i % 2 == 0;
			p += up ? 1.0 : -0.6;
			closes[i] = p;
			vol[i] = up ? 3_000_000 : 1_000_000;
		}
		List<PriceCandle> c = new ArrayList<>();
		for (int i = 0; i < 80; i++) {
			double o = i == 0 ? closes[0] : closes[i - 1];
			c.add(new PriceCandle("T", START.plusDays(i), bd(o), bd(Math.max(o, closes[i]) + 0.1), bd(Math.min(o, closes[i]) - 0.1), bd(closes[i]), vol[i]));
		}
		ChartStudy s = study(c);

		assertNotNull(s.upDownVolumeRatio20());
		assertTrue(s.upDownVolumeRatio20() > 1.2, "up/down volume " + s.upDownVolumeRatio20());
		assertTrue(s.notes().stream().anyMatch(n -> n.contains("accumulation")));
	}

	@Test
	void missingVolumeDoesNotBreakTheStudy() {
		List<PriceCandle> noVol = new ArrayList<>();
		for (PriceCandle k : fromCloses(ramp(100, 130, 80))) {
			noVol.add(new PriceCandle("T", k.getCandleDate(), k.getOpen(), k.getHigh(), k.getLow(), k.getClose(), null));
		}
		ChartStudy s = study(noVol);

		assertNull(s.volumeRatio20v60());
		assertNull(s.upDownVolumeRatio20());
	}

	// ---- support / resistance ----

	@Test
	void swingPointsBecomeSupportBelowAndResistanceAbove() {
		// A choppy range 95..105 ending mid-range.
		double[] closes = new double[80];
		for (int i = 0; i < 80; i++) closes[i] = 100 + 5 * Math.sin(i / 3.0);
		ChartStudy s = study(fromCloses(closes));

		assertNotNull(s.support());
		assertNotNull(s.resistance());
		assertTrue(s.support() < s.lastClose() && s.resistance() > s.lastClose());
	}

	// ---- relative strength ----

	@Test
	void outperformingTheBenchmarkAddsRelativeStrength() {
		List<PriceCandle> stock = fromCloses(ramp(100, 140, 90));
		List<PriceCandle> flatSpy = fromCloses(ramp(100, 100.5, 90));

		ChartStudy rel = ChartReader.study(stock, flatSpy).orElseThrow();
		ChartStudy alone = study(stock);

		assertNotNull(rel.relStrength60d());
		assertTrue(rel.relStrength60d() > 5);
		assertTrue(rel.score() >= alone.score(), "outperformance must not lower the score");
		assertTrue(rel.notes().stream().anyMatch(n -> n.contains("Relative strength")));
	}

	@Test
	void anUptrendScoreIsHigherThanADowntrendScoreAndBothAreBounded() {
		double up = study(fromCloses(ramp(50, 150, 260))).score();
		double down = study(fromCloses(ramp(150, 50, 260))).score();

		assertTrue(up > down);
		assertTrue(up <= 1.0 && down >= -1.0);
	}

	@Test
	void renderProducesReadableEvidenceForAnAnalyst() {
		String text = study(fromCloses(ramp(50, 150, 260))).render();

		assertTrue(text.contains("Trend:") && text.contains("Momentum:") && text.contains("Returns:"));
	}
}
