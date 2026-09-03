package com.argus.technical;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.ta4j.core.Bar;
import org.ta4j.core.BarSeries;
import org.ta4j.core.BaseBar;
import org.ta4j.core.BaseBarSeries;
import org.ta4j.core.indicators.MACDIndicator;
import org.ta4j.core.indicators.averages.EMAIndicator;
import org.ta4j.core.indicators.bollinger.PercentBIndicator;
import org.ta4j.core.indicators.helpers.ClosePriceIndicator;
import org.ta4j.core.num.DecimalNum;
import org.ta4j.core.num.Num;

/**
 * Pure, deterministic technical-indicator math over a ticker's daily candles (Agent 10 — Technical
 * Analysis). No LLM, no I/O — every function takes candles ordered <b>ascending</b> by date
 * (oldest first; {@link PriceCandleRepository}'s own query returns most-recent-first, so callers
 * must reverse it) and returns a plain number, same "no number comes from an LLM" discipline as
 * {@link com.argus.recommendation.ProbabilityScoringEngine}.
 *
 * <p>{@link #rsi14} uses the simple (non-Wilder-smoothed) average-gain/average-loss variant — it
 * only needs 15 candles of history rather than Wilder's exponential-smoothing warm-up, and is fully
 * deterministic to test. It's a well-established, if simplified, textbook RSI, not a from-scratch
 * indicator.
 *
 * <p>{@link #macdHistogram} and {@link #bollingerPercentB} are backed by the real open-source
 * <a href="https://github.com/ta4j/ta4j">ta4j</a> library rather than more hand-rolled math — added
 * for the two indicators the app didn't already have, deliberately alongside (not replacing) the
 * hand-rolled RSI/SMA/drawdown above, which are already tested and live.
 */
public final class TechnicalIndicators {

	public static final int RSI_PERIOD = 14;
	public static final int SMA_SHORT_PERIOD = 20;
	public static final int SMA_LONG_PERIOD = 50;
	public static final int DRAWDOWN_LOOKBACK_DAYS = 60;
	private static final int MACD_SHORT_PERIOD = 12;
	private static final int MACD_LONG_PERIOD = 26;
	private static final int MACD_SIGNAL_PERIOD = 9;
	private static final int MACD_MIN_CANDLES = MACD_LONG_PERIOD + MACD_SIGNAL_PERIOD;
	private static final int BOLLINGER_PERIOD = 20;
	private static final double BOLLINGER_K = 2.0;

	private TechnicalIndicators() {
		// pure static utility
	}

	/** Simple moving average of the closing price over the most recent {@code period} candles.
	 * Empty when there isn't enough history yet. */
	public static Optional<BigDecimal> sma(List<PriceCandle> ascending, int period) {
		if (ascending.size() < period) {
			return Optional.empty();
		}
		List<PriceCandle> window = ascending.subList(ascending.size() - period, ascending.size());
		BigDecimal sum = window.stream().map(PriceCandle::getClose).reduce(BigDecimal.ZERO, BigDecimal::add);
		return Optional.of(sum.divide(BigDecimal.valueOf(period), MathContext.DECIMAL64));
	}

	/** 14-period RSI (see class javadoc for the simple-average variant used). Empty when there
	 * aren't at least {@link #RSI_PERIOD} + 1 candles (need that many price changes). */
	public static Optional<BigDecimal> rsi14(List<PriceCandle> ascending) {
		if (ascending.size() < RSI_PERIOD + 1) {
			return Optional.empty();
		}
		List<PriceCandle> window = ascending.subList(ascending.size() - RSI_PERIOD - 1, ascending.size());
		BigDecimal totalGain = BigDecimal.ZERO;
		BigDecimal totalLoss = BigDecimal.ZERO;
		for (int i = 1; i < window.size(); i++) {
			BigDecimal change = window.get(i).getClose().subtract(window.get(i - 1).getClose());
			if (change.signum() > 0) {
				totalGain = totalGain.add(change);
			}
			else if (change.signum() < 0) {
				totalLoss = totalLoss.add(change.abs());
			}
		}
		BigDecimal avgGain = totalGain.divide(BigDecimal.valueOf(RSI_PERIOD), MathContext.DECIMAL64);
		BigDecimal avgLoss = totalLoss.divide(BigDecimal.valueOf(RSI_PERIOD), MathContext.DECIMAL64);
		if (avgLoss.signum() == 0) {
			return Optional.of(BigDecimal.valueOf(100)); // no losses in the window — maximally overbought
		}
		if (avgGain.signum() == 0) {
			return Optional.of(BigDecimal.ZERO); // no gains in the window — maximally oversold
		}
		BigDecimal rs = avgGain.divide(avgLoss, MathContext.DECIMAL64);
		BigDecimal rsi = BigDecimal.valueOf(100)
				.subtract(BigDecimal.valueOf(100).divide(BigDecimal.ONE.add(rs), MathContext.DECIMAL64));
		return Optional.of(rsi.setScale(2, RoundingMode.HALF_UP));
	}

	/** % below the highest daily high over the last {@code lookbackDays} candles (negative = below
	 * the high, 0 = at or above it). Empty when there isn't enough history to be meaningful (fewer
	 * than half the lookback window). */
	public static Optional<Double> drawdownFromHighPct(List<PriceCandle> ascending, int lookbackDays) {
		if (ascending.size() < lookbackDays / 2) {
			return Optional.empty();
		}
		int from = Math.max(0, ascending.size() - lookbackDays);
		List<PriceCandle> window = ascending.subList(from, ascending.size());
		BigDecimal high = window.stream().map(PriceCandle::getHigh).max(BigDecimal::compareTo)
				.orElseThrow();
		BigDecimal lastClose = ascending.get(ascending.size() - 1).getClose();
		if (high.signum() == 0) {
			return Optional.of(0.0);
		}
		BigDecimal pct = lastClose.subtract(high).divide(high, MathContext.DECIMAL64)
				.multiply(BigDecimal.valueOf(100));
		return Optional.of(Math.min(0.0, pct.doubleValue())); // never report a "positive drawdown"
	}

	/** MACD histogram (MACD line − its 9-period EMA signal line) at the most recent candle —
	 * positive means bullish momentum (MACD above its own signal line), negative means bearish.
	 * Empty below {@value #MACD_MIN_CANDLES} candles ({@value #MACD_LONG_PERIOD}-period EMA +
	 * {@value #MACD_SIGNAL_PERIOD}-period signal-line EMA), ta4j's own standard 12/26/9 periods. */
	public static Optional<Double> macdHistogram(List<PriceCandle> ascending) {
		if (ascending.size() < MACD_MIN_CANDLES) {
			return Optional.empty();
		}
		BarSeries series = toBarSeries(ascending);
		ClosePriceIndicator closePrice = new ClosePriceIndicator(series);
		MACDIndicator macd = new MACDIndicator(closePrice, MACD_SHORT_PERIOD, MACD_LONG_PERIOD);
		EMAIndicator signal = new EMAIndicator(macd, MACD_SIGNAL_PERIOD);
		int lastIndex = series.getEndIndex();
		double histogram = macd.getValue(lastIndex).minus(signal.getValue(lastIndex)).doubleValue();
		return Optional.of(histogram);
	}

	/** Where the last close sits relative to its 20-period Bollinger Bands: 0 = at the lower band,
	 * 1 = at the upper band, below 0 / above 1 = a real band breach. Empty below {@value
	 * #BOLLINGER_PERIOD} candles. */
	public static Optional<Double> bollingerPercentB(List<PriceCandle> ascending) {
		if (ascending.size() < BOLLINGER_PERIOD) {
			return Optional.empty();
		}
		BarSeries series = toBarSeries(ascending);
		ClosePriceIndicator closePrice = new ClosePriceIndicator(series);
		PercentBIndicator percentB = new PercentBIndicator(closePrice, BOLLINGER_PERIOD, BOLLINGER_K);
		return Optional.of(percentB.getValue(series.getEndIndex()).doubleValue());
	}

	/** Adapts our candles into a ta4j {@link BarSeries} — one daily {@link BaseBar} per {@link
	 * PriceCandle}, in the same ascending order already required by every function here. Volume is
	 * carried through when present; ta4j only needs a non-null {@link Num}, so a missing volume
	 * (nullable in {@link PriceCandle}) becomes zero rather than skipping the bar. */
	private static BarSeries toBarSeries(List<PriceCandle> ascending) {
		List<Bar> bars = new ArrayList<>(ascending.size());
		for (PriceCandle c : ascending) {
			Instant begin = c.getCandleDate().atStartOfDay(java.time.ZoneOffset.UTC).toInstant();
			Num volume = DecimalNum.valueOf(c.getVolume() == null ? 0L : c.getVolume());
			bars.add(new BaseBar(Duration.ofDays(1), begin, begin.plus(Duration.ofDays(1)),
					DecimalNum.valueOf(c.getOpen()), DecimalNum.valueOf(c.getHigh()),
					DecimalNum.valueOf(c.getLow()), DecimalNum.valueOf(c.getClose()), volume,
					DecimalNum.valueOf(0), 0L));
		}
		return new BaseBarSeries("candles", bars);
	}

	/** The full picture for one ticker as of its most recent candle — {@link Optional#empty()} when
	 * there isn't enough history yet for a meaningful read (fewer than {@link #RSI_PERIOD} + 1
	 * candles, the shortest requirement of any of these). */
	public static Optional<Snapshot> snapshot(List<PriceCandle> ascending) {
		Optional<BigDecimal> rsi = rsi14(ascending);
		if (rsi.isEmpty()) {
			return Optional.empty();
		}
		Optional<Double> drawdown = drawdownFromHighPct(ascending, DRAWDOWN_LOOKBACK_DAYS);
		return Optional.of(new Snapshot(sma(ascending, SMA_SHORT_PERIOD).orElse(null),
				sma(ascending, SMA_LONG_PERIOD).orElse(null), rsi.get(), drawdown.orElse(0.0),
				ascending.get(ascending.size() - 1).getClose(), macdHistogram(ascending).orElse(null),
				bollingerPercentB(ascending).orElse(null)));
	}

	/** @param sma20 null when fewer than {@value #SMA_SHORT_PERIOD} candles of history exist
	 *  @param sma50 null when fewer than {@value #SMA_LONG_PERIOD} candles of history exist
	 *  @param macdHistogram null when fewer than {@value #MACD_MIN_CANDLES} candles of history exist
	 *  @param bollingerPercentB null when fewer than {@value #BOLLINGER_PERIOD} candles of history exist */
	public record Snapshot(BigDecimal sma20, BigDecimal sma50, BigDecimal rsi14,
			double drawdownFromHighPct, BigDecimal lastClose, Double macdHistogram, Double bollingerPercentB) {
	}
}
