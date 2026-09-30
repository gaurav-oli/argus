package com.argus.recommendation;

import com.argus.deepanalysis.DeepVerdict;
import com.argus.deepanalysis.DeepView;
import com.argus.technical.ChartStudy;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;

/**
 * What price to act at, for a human following a call — the question the Intelligence page's conviction score and
 * hold period alone don't answer. Three deterministic levels, all derived the same way the paper Investor already
 * trades (nothing here is an LLM guess):
 * <ul>
 *   <li><b>Entry</b> — the current price, with a pullback note when it has already run up near-term (chasing a big
 *       move is one of the policy's own abstain reasons; the note carries that same caution here).</li>
 *   <li><b>Exit</b> — Agent 10's nearest resistance (support, for an avoid/short) when the chart has one; otherwise
 *       a house take-profit band ({@value #DEFAULT_TARGET_PCT}, scaled by conviction) so the field is never blank.
 *       A <b>core holding</b> (long hold, actionable conviction, and fundamentals not reading RICH) gets no fixed
 *       exit at all — Agent 11's invalidation price is the only reason to leave, and dips toward support are a
 *       reason to add, not sell.</li>
 *   <li><b>Stop</b> — the same chart-derived level ({@link StopLoss}) the paper Investor actually trades.</li>
 * </ul>
 * Pure: no I/O, so every field traces to the chart, the deep verdict or a named house constant.
 */
public final class PriceGuidance {

	/** A same-day move at or beyond this is "already run" — the entry note turns cautious rather than silent. */
	static final double EXTENDED_MOVE_PCT = 0.06;
	/** House take-profit band when the chart has no resistance to anchor to: modest for a week, generous for a quarter. */
	static final double DEFAULT_TARGET_PCT_SHORT = 0.08;
	static final double DEFAULT_TARGET_PCT_LONG = 0.20;
	static final int CORE_HOLD_DAYS = 90;

	private PriceGuidance() {
	}

	public enum Style { SWING, CORE_HOLD }

	public record Guidance(BigDecimal buyPrice, String buyNote, BigDecimal sellPrice, String sellNote,
			BigDecimal stopPrice, String stopNote, Style style) {
	}

	/**
	 * @param action        the call (only STRONG_BUY/BUY/AVOID/STRONG_AVOID produce guidance; WATCH does not)
	 * @param lastPrice     live price; null means no guidance can be given
	 * @param valuation     the reverse-DCF verdict (CHEAP/FAIR/RICH) if known, else null
	 */
	public static Guidance build(RecommendationAction action, int holdDays, Double lastPrice, ChartStudy chart,
			DeepView deep, String valuation) {
		if (action == null || action == RecommendationAction.WATCH || lastPrice == null || lastPrice <= 0) {
			return null;
		}
		boolean bullish = action.direction() == SignalDirection.BULLISH;
		double price = lastPrice;

		BigDecimal buy = money(price);
		String buyNote;
		if (chart != null && bullish && chart.support() != null && price > chart.support() * (1 + EXTENDED_MOVE_PCT)) {
			buyNote = String.format(Locale.ROOT, "at market (%.2f), though it's already %.0f%% above support (%.2f) — a pullback there is a better entry",
					price, (price / chart.support() - 1) * 100, chart.support());
		}
		else if (chart != null && !bullish && chart.resistance() != null && price < chart.resistance() * (1 - EXTENDED_MOVE_PCT)) {
			buyNote = String.format(Locale.ROOT, "at market (%.2f) if selling/shorting — already %.0f%% below resistance (%.2f)",
					price, (1 - price / chart.resistance()) * 100, chart.resistance());
		}
		else {
			buyNote = String.format(Locale.ROOT, "at or near the current price (%.2f)", price);
		}

		BigDecimal stop = StopLoss.stopFor(action.direction(), chart, price);
		String stopNote = "Agent 10's chart-derived stop — the level that says the setup failed";

		boolean coreHold = bullish && holdDays >= CORE_HOLD_DAYS && !"RICH".equals(valuation)
				&& (deep == null || deep.verdict() != DeepVerdict.NOT_WORTH_BUYING) && (deep == null || !deep.atRisk());

		if (coreHold) {
			return new Guidance(buy, buyNote, null,
					"No fixed sell target — this reads as a core holding. Keep it (and add on dips toward support"
							+ (chart != null && chart.support() != null ? String.format(Locale.ROOT, " near %.2f", chart.support()) : "")
							+ ") as long as the thesis holds; exit only if it breaks the stop below or Agent 11 reverses the call.",
					stop, stopNote, Style.CORE_HOLD);
		}

		BigDecimal sell;
		String sellNote;
		if (chart != null && bullish && chart.resistance() != null && chart.resistance() > price) {
			sell = money(chart.resistance());
			sellNote = "Agent 10's nearest resistance — the first level likely to meet selling";
		}
		else if (chart != null && !bullish && chart.support() != null && chart.support() < price) {
			sell = money(chart.support());
			sellNote = "Agent 10's nearest support — the first level a short would look to cover at";
		}
		else {
			double pct = holdDays <= 7 ? DEFAULT_TARGET_PCT_SHORT : holdDays >= CORE_HOLD_DAYS ? DEFAULT_TARGET_PCT_LONG : (DEFAULT_TARGET_PCT_SHORT + DEFAULT_TARGET_PCT_LONG) / 2;
				sell = money(bullish ? price * (1 + pct) : price * (1 - pct));
			sellNote = String.format(Locale.ROOT,
					"No chart level to anchor to — a house take-profit band for a %d-day call (%.0f%% from entry)", holdDays, pct * 100);
		}
		return new Guidance(buy, buyNote, sell, sellNote, stop, stopNote, Style.SWING);
	}

	private static BigDecimal money(double v) {
		int decimals = v >= 1 ? 2 : v >= 0.01 ? 4 : 6;
		double factor = Math.pow(10, decimals);
		return BigDecimal.valueOf(Math.round(v * factor) / factor).setScale(decimals, RoundingMode.HALF_UP);
	}
}
