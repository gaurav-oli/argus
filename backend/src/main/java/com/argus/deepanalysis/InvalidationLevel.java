package com.argus.deepanalysis;

/**
 * The price whose breach would prove a verdict wrong. The model proposes one, but a model-invented level can be nonsense
 * (above the price for a buy, or 80% away), so it is validated against the price and replaced by a chart-derived level when
 * unusable: just beyond the nearest support (for a buy) or resistance (for an avoid) if that is a sensible distance away,
 * otherwise 2.5 average daily ranges (clamped 5–15%) — the same rule the paper Investor uses for its stops.
 * Pure.
 */
public final class InvalidationLevel {

	private static final double MIN_DISTANCE = 0.02;
	private static final double MAX_DISTANCE = 0.50;

	private InvalidationLevel() {
	}

	public record Resolved(Double price, boolean modelLevelUsed) {
	}

	/**
	 * @param proposed  the model's level (may be null)
	 * @param price     current price (null → nothing can be validated)
	 * @param support   nearest chart support below price (may be null)
	 * @param resistance nearest chart resistance above price (may be null)
	 * @param atrPct    average true range as percent of price (may be null)
	 * @return the level to use; price null when the verdict has no directional thesis to invalidate
	 */
	public static Resolved resolve(DeepVerdict verdict, Double proposed, Double price, Double support, Double resistance, Double atrPct) {
		if (verdict == DeepVerdict.WAIT || price == null || price <= 0) {
			return new Resolved(null, false);
		}
		boolean buy = verdict == DeepVerdict.WORTH_BUYING;
		if (proposed != null && proposed > 0) {
			double dist = buy ? (price - proposed) / price : (proposed - price) / price;
			if (dist >= MIN_DISTANCE && dist <= MAX_DISTANCE) {
				return new Resolved(round(proposed), true);
			}
		}
		double d = atrPct == null ? 0.10 : Math.max(0.05, Math.min(0.15, 2.5 * atrPct / 100.0));
		double level = buy ? price * (1 - d) : price * (1 + d);
		if (buy && support != null && support < price * 0.97 && support * 0.99 > level) {
			level = support * 0.99;
		}
		else if (!buy && resistance != null && resistance > price * 1.03 && resistance * 1.01 < level) {
			level = resistance * 1.01;
		}
		return new Resolved(round(level), false);
	}

	private static double round(double v) {
		return Math.round(v * 100.0) / 100.0;
	}
}
