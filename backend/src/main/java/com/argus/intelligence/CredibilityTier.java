package com.argus.intelligence;

/**
 * Source credibility bands (Story 4.3, FR-9). A source's 0–100 score maps to exactly one tier;
 * {@link #BLOCKED} (&lt;10) means its signals are excluded from downstream recommendations.
 */
public enum CredibilityTier {

	PLATINUM(90, 100),
	GOLD(75, 89),
	SILVER(50, 74),
	BRONZE(25, 49),
	FLAGGED(10, 24),
	BLOCKED(0, 9);

	private final int min;
	private final int max;

	CredibilityTier(int min, int max) {
		this.min = min;
		this.max = max;
	}

	public int min() {
		return min;
	}

	public int max() {
		return max;
	}

	/** The tier a (clamped 0–100) score falls into. */
	public static CredibilityTier forScore(int score) {
		for (CredibilityTier tier : values()) {
			if (score >= tier.min && score <= tier.max) {
				return tier;
			}
		}
		// Unreachable for a clamped 0–100 score; fail safe to the most cautious band.
		return BLOCKED;
	}
}
