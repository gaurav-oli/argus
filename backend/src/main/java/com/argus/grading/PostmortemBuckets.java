package com.argus.grading;

/**
 * The five post-mortem buckets (strategy rebuild, Phase 2), decided by numbers only and always measured against SPY.
 * Rules are checked in this order and the first match decides, so every trade lands in exactly one bucket.
 *
 * <ol>
 *   <li>{@link Bucket#LUCKY_WIN} — beat SPY, but the agents it relied on were wrong on their own timeframe.</li>
 *   <li>{@link Bucket#GOOD_RESULT} — beat SPY.</li>
 *   <li>{@link Bucket#BAD_EXECUTION} — lagged SPY, but holding to the planned date would have beaten it.</li>
 *   <li>{@link Bucket#BAD_LUCK} — lagged SPY, and the loss came from a shock (market-shock day or a gap far beyond the
 *       stock's normal range).</li>
 *   <li>{@link Bucket#BAD_IDEA} — everything else.</li>
 * </ol>
 */
public final class PostmortemBuckets {

	public enum Bucket {
		LUCKY_WIN("Bad idea, lucky win"),
		GOOD_RESULT("Good idea, good result"),
		BAD_EXECUTION("Good idea, bad execution"),
		BAD_LUCK("Good idea, bad luck"),
		BAD_IDEA("Bad idea");

		private final String label;

		Bucket(String label) {
			this.label = label;
		}

		public String label() {
			return label;
		}
	}

	private PostmortemBuckets() {
	}

	/**
	 * @param excessPct     the trade's return vs SPY, direction-adjusted, after costs
	 * @param holdExcessPct the same had it been held to the planned date; null if that date hasn't come yet
	 * @param reliedRight   whether the agents it relied on were right (strength-weighted); null if not yet graded
	 * @param shock         whether a shock hit it while open
	 */
	public static Bucket classify(double excessPct, Double holdExcessPct, Boolean reliedRight, boolean shock) {
		if (excessPct > 0) {
			return Boolean.FALSE.equals(reliedRight) ? Bucket.LUCKY_WIN : Bucket.GOOD_RESULT;
		}
		if (holdExcessPct != null && holdExcessPct > 0) {
			return Bucket.BAD_EXECUTION;
		}
		return shock ? Bucket.BAD_LUCK : Bucket.BAD_IDEA;
	}
}
