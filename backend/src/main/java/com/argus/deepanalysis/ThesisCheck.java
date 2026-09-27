package com.argus.deepanalysis;

import java.time.LocalDate;
import java.util.Optional;

/**
 * Decides whether a standing verdict's thesis has been undermined <em>since</em> it was written, without re-running the slow
 * analysis. Two objective triggers only — a verdict the model wrote a price level for is held to that level, and a filing that
 * landed after the analysis and contradicts it is treated as new evidence:
 * <ul>
 *   <li>the price crossed the invalidation level (below it for WORTH_BUYING, above it for NOT_WORTH_BUYING);</li>
 *   <li>a filing made after the analysis scored against the call (≤ −0.35 for a buy, ≥ +0.35 for an avoid), or a buy whose
 *       newest earnings release <em>lowered</em> guidance.</li>
 * </ul>
 * WAIT makes no claim to invalidate. Pure.
 */
public final class ThesisCheck {

	static final double FILING_THRESHOLD = 0.35;

	private ThesisCheck() {
	}

	/**
	 * @param filingFiledAt date of the newest digest (null = none)
	 * @param filingScore   its score (-1..+1)
	 * @param guidance      the newest earnings digest's guidance (RAISED / MAINTAINED / LOWERED / NONE), may be null
	 * @return the reason the thesis is at risk, or empty when it still stands
	 */
	public static Optional<String> atRisk(DeepVerdict verdict, LocalDate analyzedOn, Double invalidationPrice, Double lastPrice,
			LocalDate filingFiledAt, Double filingScore, String guidance) {
		if (verdict == null || verdict == DeepVerdict.WAIT) {
			return Optional.empty();
		}
		boolean buy = verdict == DeepVerdict.WORTH_BUYING;
		if (invalidationPrice != null && lastPrice != null && lastPrice > 0
				&& (buy ? lastPrice <= invalidationPrice : lastPrice >= invalidationPrice)) {
			return Optional.of(String.format(java.util.Locale.ROOT, "Price %.2f has %s the invalidation level %.2f.", lastPrice,
					buy ? "fallen through" : "risen through", invalidationPrice));
		}
		if (filingFiledAt != null && filingFiledAt.isAfter(analyzedOn) && filingScore != null) {
			if (buy && (filingScore <= -FILING_THRESHOLD || "LOWERED".equals(guidance))) {
				return Optional.of("A filing since the analysis (" + filingFiledAt + ") reads negative"
						+ ("LOWERED".equals(guidance) ? " and lowered guidance." : "."));
			}
			if (!buy && filingScore >= FILING_THRESHOLD) {
				return Optional.of("A filing since the analysis (" + filingFiledAt + ") reads clearly positive.");
			}
		}
		return Optional.empty();
	}
}
