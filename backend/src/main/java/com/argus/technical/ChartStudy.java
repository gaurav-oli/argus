package com.argus.technical;

import java.time.LocalDate;
import java.util.List;
import java.util.Locale;

/**
 * A full read of a stock's daily chart — trend, momentum, volume, support/resistance, candlestick
 * patterns and strength versus the market — computed deterministically from stored candles by {@link
 * ChartReader}. Where the old Agent 10 looked only at RSI and two moving averages, this is the chart
 * study a human technician would do; it feeds both Agent 10's quick signal and Agent 11's deep analysis.
 *
 * <p>Every field is nullable when there isn't enough history for it; nothing is guessed.
 *
 * @param score  -1 (strongly bearish) .. +1 (strongly bullish), a fixed weighted blend of the components
 * @param notes  the evidence behind the score, one plain-English line each (also what the LLM analyst reads)
 */
public record ChartStudy(
		int bars, LocalDate asOf, double lastClose,
		Double ret5d, Double ret20d, Double ret60d,
		Double sma20, Double sma50, Double sma200, Trend trend,
		Double rsi14, Double macdHistogram, Double bollingerPercentB, Double atrPct,
		Double volumeRatio20v60, Double upDownVolumeRatio20,
		Double pctOf52wRange, Double drawdown60dPct,
		Double support, Double resistance,
		List<CandlePattern> patterns,
		Double relStrength20d, Double relStrength60d,
		double score, String bias, List<String> notes) {

	public enum Trend { UPTREND, DOWNTREND, SIDEWAYS }

	/**
	 * A candlestick pattern seen in the last few sessions.
	 *
	 * @param bias BULLISH / BEARISH / NEUTRAL — the pattern's usual reversal/continuation implication
	 */
	public record CandlePattern(LocalDate date, String name, String bias, String context) {
	}

	/** Multi-line, plain-text rendering for an LLM prompt or a UI panel. */
	public String render() {
		StringBuilder sb = new StringBuilder();
		sb.append(String.format(Locale.ROOT, "Chart as of %s (%d daily bars), last close %.2f. Deterministic chart score %+.2f (%s).%n",
				asOf, bars, lastClose, score, bias));
		for (String n : notes) {
			sb.append("- ").append(n).append('\n');
		}
		return sb.toString();
	}
}
