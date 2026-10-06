package com.argus.technical;

import com.argus.technical.ChartStudy.CandlePattern;
import com.argus.technical.ChartStudy.Trend;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Deterministic chart study over daily candles — no LLM, no I/O. Reads what a human technician reads:
 * <ul>
 *   <li><b>Trend</b> — price vs. its 20/50/200-day averages and whether they are stacked in order
 *       (uptrend: close &gt; SMA50 &gt; SMA200; downtrend: the reverse).</li>
 *   <li><b>Momentum</b> — RSI, MACD histogram, Bollinger %B (via {@link TechnicalIndicators}), plus 5/20/60-day returns.</li>
 *   <li><b>Volume</b> — recent vs. longer-run average volume, and whether volume is heavier on up days or down days.</li>
 *   <li><b>Support / resistance</b> — the nearest swing lows below and swing highs above price.</li>
 *   <li><b>Candlestick patterns</b> in the last 5 sessions — doji, hammer, shooting star, bullish/bearish
 *       engulfing, morning/evening star — each read in the context of the move that preceded it (a hammer
 *       after a decline is a reversal hint; the same shape in an uptrend is noise).</li>
 *   <li><b>Relative strength</b> — excess return over a benchmark (SPY) across 20 and 60 sessions.</li>
 * </ul>
 * The {@link ChartStudy#score()} is a fixed, documented blend; it is a chart-only opinion and is never
 * treated as more than one input to a recommendation.
 */
public final class ChartReader {

	static final int MIN_BARS = 30;
	private static final int PATTERN_WINDOW = 5;
	private static final int PIVOT_LOOKBACK = 90;
	private static final int PIVOT_SIDE = 3;

	private ChartReader() {
	}

	/**
	 * @param ascending          the ticker's candles, oldest first
	 * @param benchmarkAscending the benchmark's (SPY) candles, oldest first; may be empty (no relative strength)
	 * @return empty when there are fewer than {@value #MIN_BARS} candles — too little to study honestly
	 */
	public static Optional<ChartStudy> study(List<PriceCandle> ascending, List<PriceCandle> benchmarkAscending) {
		if (ascending == null || ascending.size() < MIN_BARS) {
			return Optional.empty();
		}
		int n = ascending.size();
		double[] o = new double[n], h = new double[n], l = new double[n], c = new double[n], v = new double[n];
		for (int i = 0; i < n; i++) {
			PriceCandle k = ascending.get(i);
			o[i] = k.getOpen().doubleValue();
			h[i] = k.getHigh().doubleValue();
			l[i] = k.getLow().doubleValue();
			c[i] = k.getClose().doubleValue();
			v[i] = k.getVolume() == null ? 0 : k.getVolume();
		}
		double last = c[n - 1];
		List<String> notes = new ArrayList<>();
		double score = 0;

		Double ret5 = ret(c, 5), ret20 = ret(c, 20), ret60 = ret(c, 60);
		Double sma20 = sma(c, 20), sma50 = sma(c, 50), sma200 = sma(c, 200);

		// ---- trend ----
		Trend trend = Trend.SIDEWAYS;
		String trendNote;
		if (sma200 != null && sma50 != null) {
			if (last > sma50 && sma50 > sma200) {
				trend = Trend.UPTREND;
			}
			else if (last < sma50 && sma50 < sma200) {
				trend = Trend.DOWNTREND;
			}
			trendNote = String.format(Locale.ROOT, "Trend: %s — close %.2f vs SMA20 %s / SMA50 %.2f / SMA200 %.2f%s.",
					trend, last, fmt(sma20), sma50, sma200, crossNote(c));
		}
		else if (sma50 != null && sma20 != null) {
			if (last > sma20 && sma20 > sma50) {
				trend = Trend.UPTREND;
			}
			else if (last < sma20 && sma20 < sma50) {
				trend = Trend.DOWNTREND;
			}
			trendNote = String.format(Locale.ROOT,
					"Trend: %s — close %.2f vs SMA20 %.2f / SMA50 %.2f (under 200 bars, so no SMA200 yet).",
					trend, last, sma20, sma50);
		}
		else {
			trendNote = "Trend: not enough history for a moving-average read.";
		}
		notes.add(trendNote);
		score += trend == Trend.UPTREND ? 0.35 : trend == Trend.DOWNTREND ? -0.35 : 0;

		// ---- returns ----
		notes.add(String.format(Locale.ROOT, "Returns: 5d %s, 20d %s, 60d %s.", pct(ret5), pct(ret20), pct(ret60)));

		// ---- momentum ----
		Optional<TechnicalIndicators.Snapshot> snap = TechnicalIndicators.snapshot(ascending);
		Double rsi = snap.map(s -> s.rsi14().doubleValue()).orElse(null);
		Double macd = snap.map(TechnicalIndicators.Snapshot::macdHistogram).orElse(null);
		Double pctB = snap.map(TechnicalIndicators.Snapshot::bollingerPercentB).orElse(null);
		if (rsi != null) {
			String state = rsi < 30 ? " (oversold)" : rsi > 70 ? " (overbought)" : "";
			notes.add(String.format(Locale.ROOT, "Momentum: RSI %.1f%s, MACD histogram %s, Bollinger %%B %s.", rsi, state,
					macd == null ? "n/a" : macd > 0 ? "positive" : "negative", pctB == null ? "n/a" : String.format(Locale.ROOT, "%.2f", pctB)));
			score += rsi < 30 ? 0.10 : rsi > 70 ? -0.10 : 0;
		}
		if (macd != null) {
			score += macd > 0 ? 0.15 : -0.15;
		}
		Double atrPct = atrPct(h, l, c, 14);
		if (atrPct != null) {
			notes.add(String.format(Locale.ROOT, "Volatility: average true range %.1f%% of price per day.", atrPct));
		}

		// ---- volume ----
		Double volRatio = null, upDown = null;
		double vAvg20 = avg(v, n - 20, n), vAvg60 = n >= 60 ? avg(v, n - 60, n) : 0;
		if (vAvg60 > 0) {
			volRatio = vAvg20 / vAvg60;
		}
		double up = 0, down = 0;
		for (int i = Math.max(1, n - 20); i < n; i++) {
			if (c[i] > c[i - 1]) up += v[i];
			else if (c[i] < c[i - 1]) down += v[i];
		}
		if (up + down > 0 && down > 0) {
			upDown = up / down;
		}
		if (volRatio != null || upDown != null) {
			notes.add(String.format(Locale.ROOT, "Volume: 20-day average is %s of the 60-day average; over the last 20 sessions %s.",
					volRatio == null ? "n/a" : String.format(Locale.ROOT, "%.0f%%", volRatio * 100),
					upDown == null ? "up/down volume not measurable"
							: upDown > 1.2 ? String.format(Locale.ROOT, "up-day volume is %.1fx down-day volume (accumulation)", upDown)
							: upDown < 0.8 ? String.format(Locale.ROOT, "down-day volume is %.1fx up-day volume (distribution)", 1 / upDown)
							: "up and down volume are balanced"));
			if (upDown != null) {
				score += upDown > 1.2 ? 0.05 : upDown < 0.8 ? -0.05 : 0;
			}
		}

		// ---- 52-week range, drawdown ----
		int lo52 = Math.max(0, n - 252);
		double hi = max(h, lo52, n), lw = min(l, lo52, n);
		Double rangePos = hi > lw ? (last - lw) / (hi - lw) * 100 : null;
		Double drawdown = TechnicalIndicators.drawdownFromHighPct(ascending, TechnicalIndicators.DRAWDOWN_LOOKBACK_DAYS).orElse(null);
		if (rangePos != null) {
			notes.add(String.format(Locale.ROOT, "Range: at %.0f%% of its %d-bar high/low range (high %.2f, low %.2f); %s below its 60-day high.",
					rangePos, n - lo52, hi, lw, drawdown == null ? "n/a" : String.format(Locale.ROOT, "%.1f%%", Math.abs(drawdown))));
		}

		// ---- support / resistance ----
		double[] lv = levels(h, l, last);
		Double support = Double.isNaN(lv[0]) ? null : lv[0], resistance = Double.isNaN(lv[1]) ? null : lv[1];
		if (support != null || resistance != null) {
			notes.add(levelsNote(support, resistance, last));
			if (support != null && (last - support) / last <= 0.03) score += 0.05;
			if (resistance != null && (resistance - last) / last <= 0.02) score -= 0.05;
		}

		// ---- candlestick patterns ----
		List<CandlePattern> patterns = patterns(ascending, o, h, l, c);
		double patternScore = 0;
		for (CandlePattern p : patterns) {
			notes.add(String.format(Locale.ROOT, "Candlestick (%s): %s — %s, %s.", p.date(), p.name(), p.bias().toLowerCase(Locale.ROOT), p.context()));
			patternScore += "BULLISH".equals(p.bias()) ? 0.10 : "BEARISH".equals(p.bias()) ? -0.10 : 0;
		}
		score += Math.max(-0.10, Math.min(0.10, patternScore));
		if (patterns.isEmpty()) {
			notes.add("Candlestick: no notable reversal pattern in the last " + PATTERN_WINDOW + " sessions.");
		}

		// ---- relative strength vs benchmark ----
		Double rs20 = null, rs60 = null;
		if (benchmarkAscending != null && benchmarkAscending.size() >= 61) {
			double[] b = benchmarkAscending.stream().mapToDouble(k -> k.getClose().doubleValue()).toArray();
			Double b20 = ret(b, 20), b60 = ret(b, 60);
			rs20 = ret20 == null || b20 == null ? null : ret20 - b20;
			rs60 = ret60 == null || b60 == null ? null : ret60 - b60;
			if (rs20 != null || rs60 != null) {
				notes.add(String.format(Locale.ROOT, "Relative strength vs S&P 500: %s over 20 sessions, %s over 60 sessions.",
						signedPts(rs20), signedPts(rs60)));
				if (rs60 != null) score += rs60 > 5 ? 0.15 : rs60 < -5 ? -0.15 : 0;
				if (rs20 != null) score += rs20 > 3 ? 0.05 : rs20 < -3 ? -0.05 : 0;
			}
		}

		score = Math.max(-1.0, Math.min(1.0, score));
		String bias = score >= 0.25 ? "BULLISH" : score <= -0.25 ? "BEARISH" : "NEUTRAL";
		return Optional.of(new ChartStudy(n, ascending.get(n - 1).getCandleDate(), last, ret5, ret20, ret60, sma20, sma50, sma200,
				trend, rsi, macd, pctB, atrPct, volRatio, upDown, rangePos, drawdown, support, resistance, List.copyOf(patterns),
				rs20, rs60, score, bias, List.copyOf(notes)));
	}

	// ---- candlestick patterns ----

	/** Patterns among the last {@value #PATTERN_WINDOW} sessions, each with the preceding-move context. */
	static List<CandlePattern> patterns(List<PriceCandle> candles, double[] o, double[] h, double[] l, double[] c) {
		List<CandlePattern> out = new ArrayList<>();
		int n = c.length;
		for (int i = Math.max(6, n - PATTERN_WINDOW); i < n; i++) {
			double body = Math.abs(c[i] - o[i]);
			double range = h[i] - l[i];
			if (range <= 0) continue;
			double upper = h[i] - Math.max(o[i], c[i]);
			double lower = Math.min(o[i], c[i]) - l[i];
			double priorMove = (c[i - 1] / c[i - 6] - 1) * 100; // the 5 sessions before this candle
			boolean afterDecline = priorMove <= -2.0, afterRally = priorMove >= 2.0;
			LocalDate d = candles.get(i).getCandleDate();
			String down = String.format(Locale.ROOT, "after a %.1f%% decline", priorMove);
			String rally = String.format(Locale.ROOT, "after a +%.1f%% rally", priorMove);

			if (body <= 0.1 * range) {
				out.add(new CandlePattern(d, "Doji", afterDecline ? "BULLISH" : afterRally ? "BEARISH" : "NEUTRAL",
						afterDecline ? "indecision " + down + " (possible bottoming)" : afterRally ? "indecision " + rally + " (possible topping)"
								: "indecision, no strong prior move"));
			}
			if (body > 0 && lower >= 2 * body && upper <= body && afterDecline) {
				out.add(new CandlePattern(d, "Hammer", "BULLISH", "long lower shadow " + down + " — buyers rejected lower prices"));
			}
			if (body > 0 && upper >= 2 * body && lower <= body && afterRally) {
				out.add(new CandlePattern(d, "Shooting star", "BEARISH", "long upper shadow " + rally + " — sellers rejected higher prices"));
			}
			if (i >= 1) {
				boolean prevBear = c[i - 1] < o[i - 1], prevBull = c[i - 1] > o[i - 1];
				double prevBody = Math.abs(c[i - 1] - o[i - 1]);
				if (prevBear && c[i] > o[i] && o[i] <= c[i - 1] && c[i] >= o[i - 1] && body > prevBody && afterDecline) {
					out.add(new CandlePattern(d, "Bullish engulfing", "BULLISH", "up candle swallows the prior down candle " + down));
				}
				if (prevBull && c[i] < o[i] && o[i] >= c[i - 1] && c[i] <= o[i - 1] && body > prevBody && afterRally) {
					out.add(new CandlePattern(d, "Bearish engulfing", "BEARISH", "down candle swallows the prior up candle " + rally));
				}
			}
			if (i >= 2) {
				double b0 = Math.abs(c[i - 2] - o[i - 2]), b1 = Math.abs(c[i - 1] - o[i - 1]);
				double mid0 = (o[i - 2] + c[i - 2]) / 2;
				if (c[i - 2] < o[i - 2] && b1 <= 0.3 * b0 && c[i] > o[i] && c[i] > mid0 && b0 > 0 && (c[i - 2] / c[i - 7 < 0 ? 0 : i - 7] - 1) * 100 <= -2.0) {
					out.add(new CandlePattern(d, "Morning star", "BULLISH", "three-candle bottoming reversal"));
				}
				if (c[i - 2] > o[i - 2] && b1 <= 0.3 * b0 && c[i] < o[i] && c[i] < mid0 && b0 > 0 && (c[i - 2] / c[i - 7 < 0 ? 0 : i - 7] - 1) * 100 >= 2.0) {
					out.add(new CandlePattern(d, "Evening star", "BEARISH", "three-candle topping reversal"));
				}
			}
		}
		return out;
	}

	// ---- helpers ----

	/** Strictly lower than the bars before it, lower-or-equal to the bars after — so a tied double
	 * bottom (two bars sharing the same low, common in real data) still counts as one pivot. */
	private static boolean isPivotLow(double[] l, int i) {
		for (int k = 1; k <= PIVOT_SIDE; k++) {
			if (l[i] >= l[i - k] || l[i] > l[i + k]) return false;
		}
		return true;
	}

	/**
	 * The nearest swing low below and swing high above {@code price} — support and resistance. Public
	 * so the same rule can be re-run against a live intraday price ({@link ChartStudyService#withLivePrice}):
	 * the stored study is measured from the last daily close, and a big move today can put price on the
	 * other side of a level.
	 */
	public static ChartStudy.Levels levels(List<PriceCandle> ascending, double price) {
		int n = ascending.size();
		double[] h = new double[n], l = new double[n];
		for (int i = 0; i < n; i++) {
			h[i] = ascending.get(i).getHigh().doubleValue();
			l[i] = ascending.get(i).getLow().doubleValue();
		}
		double[] lv = levels(h, l, price);
		return new ChartStudy.Levels(Double.isNaN(lv[0]) ? null : lv[0], Double.isNaN(lv[1]) ? null : lv[1]);
	}

	/** {@code [support, resistance]}, NaN where none is found. */
	private static double[] levels(double[] h, double[] l, double price) {
		int n = h.length;
		double support = Double.NaN, resistance = Double.NaN;
		int from = Math.max(PIVOT_SIDE, n - PIVOT_LOOKBACK);
		for (int i = from; i < n - PIVOT_SIDE; i++) {
			if (isPivotLow(l, i) && l[i] < price && (Double.isNaN(support) || l[i] > support)) support = l[i];
			if (isPivotHigh(h, i) && h[i] > price && (Double.isNaN(resistance) || h[i] < resistance)) resistance = h[i];
		}
		return new double[] { support, resistance };
	}

	/** The human line for the levels, distances measured from {@code price}. */
	public static String levelsNote(Double support, Double resistance, double price) {
		return String.format(Locale.ROOT, "Levels: nearest support %s, nearest resistance %s.",
				support == null ? "none found" : String.format(Locale.ROOT, "%.2f (%.1f%% below)", support, (price - support) / price * 100),
				resistance == null ? "none found" : String.format(Locale.ROOT, "%.2f (%.1f%% above)", resistance, (resistance - price) / price * 100));
	}

	private static boolean isPivotHigh(double[] h, int i) {
		for (int k = 1; k <= PIVOT_SIDE; k++) {
			if (h[i] <= h[i - k] || h[i] < h[i + k]) return false;
		}
		return true;
	}

	/** Percent change of the last close vs. the close {@code sessions} bars earlier; null if too short. */
	private static Double ret(double[] c, int sessions) {
		int n = c.length;
		if (n <= sessions || c[n - 1 - sessions] <= 0) return null;
		return (c[n - 1] / c[n - 1 - sessions] - 1) * 100;
	}

	private static Double sma(double[] c, int period) {
		int n = c.length;
		if (n < period) return null;
		return avg(c, n - period, n);
	}

	private static double avg(double[] a, int from, int to) {
		if (to <= from) return 0;
		double s = 0;
		for (int i = from; i < to; i++) s += a[i];
		return s / (to - from);
	}

	private static double max(double[] a, int from, int to) {
		double m = a[from];
		for (int i = from; i < to; i++) m = Math.max(m, a[i]);
		return m;
	}

	private static double min(double[] a, int from, int to) {
		double m = a[from];
		for (int i = from; i < to; i++) m = Math.min(m, a[i]);
		return m;
	}

	private static Double atrPct(double[] h, double[] l, double[] c, int period) {
		int n = c.length;
		if (n <= period) return null;
		double sum = 0;
		for (int i = n - period; i < n; i++) {
			double tr = Math.max(h[i] - l[i], Math.max(Math.abs(h[i] - c[i - 1]), Math.abs(l[i] - c[i - 1])));
			sum += tr;
		}
		return sum / period / c[n - 1] * 100;
	}

	/** A recent golden/death cross note (SMA50 crossing SMA200 within ~20 sessions), or empty. */
	private static String crossNote(double[] c) {
		int n = c.length;
		if (n < 221) return "";
		for (int back = 0; back < 20; back++) {
			int end = n - back;
			Double a50 = smaAt(c, 50, end), a200 = smaAt(c, 200, end), p50 = smaAt(c, 50, end - 1), p200 = smaAt(c, 200, end - 1);
			if (a50 == null || a200 == null || p50 == null || p200 == null) break;
			if (p50 <= p200 && a50 > a200) return " (golden cross " + back + " sessions ago)";
			if (p50 >= p200 && a50 < a200) return " (death cross " + back + " sessions ago)";
		}
		return "";
	}

	private static Double smaAt(double[] c, int period, int endExclusive) {
		if (endExclusive < period) return null;
		return avg(c, endExclusive - period, endExclusive);
	}

	private static String pct(Double v) {
		return v == null ? "n/a" : String.format(Locale.ROOT, "%+.1f%%", v);
	}

	private static String signedPts(Double v) {
		return v == null ? "n/a" : String.format(Locale.ROOT, "%+.1f pts", v);
	}

	private static String fmt(Double v) {
		return v == null ? "n/a" : String.format(Locale.ROOT, "%.2f", v);
	}
}
