package com.argus.technical;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.List;
import java.util.Optional;

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
 */
public final class TechnicalIndicators {

	public static final int RSI_PERIOD = 14;
	public static final int SMA_SHORT_PERIOD = 20;
	public static final int SMA_LONG_PERIOD = 50;
	public static final int DRAWDOWN_LOOKBACK_DAYS = 60;

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

	/** The full picture for one ticker as of its most recent candle — {@link Optional#empty()} when
	 * there isn't enough history yet for a meaningful read (fewer than {@link #RSI_PERIOD} + 1
	 * candles, the shortest requirement of the three). */
	public static Optional<Snapshot> snapshot(List<PriceCandle> ascending) {
		Optional<BigDecimal> rsi = rsi14(ascending);
		if (rsi.isEmpty()) {
			return Optional.empty();
		}
		Optional<Double> drawdown = drawdownFromHighPct(ascending, DRAWDOWN_LOOKBACK_DAYS);
		return Optional.of(new Snapshot(sma(ascending, SMA_SHORT_PERIOD).orElse(null),
				sma(ascending, SMA_LONG_PERIOD).orElse(null), rsi.get(), drawdown.orElse(0.0),
				ascending.get(ascending.size() - 1).getClose()));
	}

	/** @param sma20 null when fewer than {@value #SMA_SHORT_PERIOD} candles of history exist
	 *  @param sma50 null when fewer than {@value #SMA_LONG_PERIOD} candles of history exist */
	public record Snapshot(BigDecimal sma20, BigDecimal sma50, BigDecimal rsi14,
			double drawdownFromHighPct, BigDecimal lastClose) {
	}
}
