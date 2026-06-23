package com.argus.recommendation;

import org.springframework.stereotype.Component;

/**
 * Computes a suggested position-size band by an explicit rule (Story 6.5, FR-15) — no LLM. The base
 * band scales with the recommendation's confidence (more confidence ⇒ larger size), then is trimmed
 * or zeroed when the user already holds a concentrated position in the stock.
 *
 * <ul>
 *   <li>Base band: confidence 0 → 0.5–1.0%, confidence 1 → 1.5–3.0% (linear).</li>
 *   <li>Existing weight ≥ {@value #HARD_CAP_PCT}% ⇒ band 0 (already heavily concentrated, no add).</li>
 *   <li>Existing weight ≥ {@value #SOFT_CAP_PCT}% ⇒ band halved and flagged.</li>
 * </ul>
 */
@Component
public class PositionSizer {

	static final double SOFT_CAP_PCT = 10.0;
	static final double HARD_CAP_PCT = 20.0;

	/**
	 * @param confidence      0–1 from the scoring engine
	 * @param currentWeightPct the stock's current weight in the portfolio (% of total), 0 if not held
	 */
	public SizingBand band(double confidence, double currentWeightPct) {
		double c = clamp01(confidence);
		double baseMin = 0.5 + 1.0 * c;   // 0.5% … 1.5%
		double baseMax = 1.0 + 2.0 * c;   // 1.0% … 3.0%

		if (currentWeightPct >= HARD_CAP_PCT) {
			return SizingBand.of(0, 0, true, String.format(
					"Already heavily concentrated (%.1f%% of portfolio) — no further add suggested.",
					currentWeightPct));
		}
		if (currentWeightPct >= SOFT_CAP_PCT) {
			return SizingBand.of(baseMin / 2, baseMax / 2, true, String.format(
					"Suggested size halved: you already hold %.1f%% in this stock.", currentWeightPct));
		}
		return SizingBand.of(baseMin, baseMax, false, String.format(
				"%.0f%% confidence ⇒ a %.1f–%.1f%% starter position.", c * 100, baseMin, baseMax));
	}

	private static double clamp01(double v) {
		return Math.max(0.0, Math.min(1.0, v));
	}
}
