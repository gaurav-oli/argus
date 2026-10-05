package com.argus.recommendation;

import com.argus.deepanalysis.DeepVerdict;
import com.argus.deepanalysis.DeepView;

/**
 * Is a stock someone already owns worth continuing to accumulate for the long run, or does it look
 * like it should be reconsidered? Deliberately reuses what Argus already computes rather than a new,
 * possibly-conflicting judgment: {@link PriceGuidance}'s existing {@code CORE_HOLD} style (bullish,
 * long hold, fundamentals not RICH, Agent 11 not against it) already answers "is this a genuine
 * long-term business, not just today's entry price" — a question kept separate here from whether
 * today happens to be a good moment to buy more (that's {@link PriceGuidance.Guidance#buyNote()}).
 *
 * <p>Stability comes for free: Agent 11's {@link DeepView#atRisk()} only flips on two objective
 * triggers (a broken invalidation price, or a contradicting filing) — see {@code ThesisCheck} — never
 * on routine daily noise, so this classification doesn't flap with it.
 *
 * <p>Never forces a confident call with thin evidence: no recommendation, or one that is neither a
 * clear core-hold nor a clear negative read, lands in {@link Verdict#NOT_ENOUGH_DATA} rather than a
 * guess. Pure — every verdict traces to an existing recommendation/deep-analysis field.
 */
public final class HoldingOutlook {

	private HoldingOutlook() {
	}

	public enum Verdict {
		KEEP("Keep — long-term"),
		RECONSIDER("Reconsider"),
		NOT_ENOUGH_DATA("Not enough data yet");

		private final String label;

		Verdict(String label) {
			this.label = label;
		}

		public String label() {
			return label;
		}
	}

	public record Outlook(Verdict verdict, String reason) {
	}

	private static final Outlook NO_RECOMMENDATION =
			new Outlook(Verdict.NOT_ENOUGH_DATA, "Argus hasn't analyzed this ticker yet.");
	private static final Outlook NOT_YET_CONVICTION =
			new Outlook(Verdict.NOT_ENOUGH_DATA, "Not yet a high-enough-conviction long-term call.");

	public static Outlook classify(Recommendation r, DeepView deep, PriceGuidance.Guidance guidance) {
		if (r == null) {
			return NO_RECOMMENDATION;
		}
		if (guidance != null && guidance.style() == PriceGuidance.Style.CORE_HOLD) {
			String reason = (deep != null && deep.headline() != null && !deep.headline().isBlank())
					? deep.headline() : r.getThesis();
			return new Outlook(Verdict.KEEP, reason);
		}
		if (deep != null && deep.atRisk()) {
			return new Outlook(Verdict.RECONSIDER, deep.atRiskReason());
		}
		if (deep != null && deep.verdict() == DeepVerdict.NOT_WORTH_BUYING) {
			return new Outlook(Verdict.RECONSIDER, "Agent 11 currently reads this as not worth buying at today's levels.");
		}
		if (r.getAction() == RecommendationAction.AVOID || r.getAction() == RecommendationAction.STRONG_AVOID) {
			return new Outlook(Verdict.RECONSIDER, "Agent 5 currently reads this as " + r.getAction().label().toLowerCase() + ".");
		}
		return NOT_YET_CONVICTION;
	}
}
