package com.argus.recommendation;

import com.argus.technical.ChartStudy;
import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * A protective stop derived from Agent 10's chart: shared by the paper Investor (the actual stop on a simulated
 * position) and the Intelligence page's price guidance (what a human following the call would set). {@value
 * #ATR_MULTIPLE}× the average daily range, clamped to 5–15%, tightened to just beyond the nearest support (for a
 * long) or resistance (for a short) when that level is closer but not closer than 3% — a level the chart says
 * should hold, whose break says the setup failed. With no chart history, a flat 10%. Pure.
 */
public final class StopLoss {

	static final double MIN_STOP = 0.05;
	static final double MAX_STOP = 0.15;
	static final double DEFAULT_STOP = 0.10;
	static final double ATR_MULTIPLE = 2.5;

	private StopLoss() {
	}

	public static BigDecimal stopFor(SignalDirection direction, ChartStudy chart, double entry) {
		double d = DEFAULT_STOP;
		if (chart != null && chart.atrPct() != null) {
			d = Math.max(MIN_STOP, Math.min(MAX_STOP, ATR_MULTIPLE * chart.atrPct() / 100.0));
		}
		double stop = direction == SignalDirection.BULLISH ? entry * (1 - d) : entry * (1 + d);
		if (chart != null) {
			if (direction == SignalDirection.BULLISH && chart.support() != null) {
				double level = chart.support() * 0.99;
				if (level < entry * 0.97 && level > stop) stop = level;
			}
			else if (direction == SignalDirection.BEARISH && chart.resistance() != null) {
				double level = chart.resistance() * 1.01;
				if (level > entry * 1.03 && level < stop) stop = level;
			}
		}
		return BigDecimal.valueOf(stop).setScale(6, RoundingMode.HALF_UP);
	}
}
