package com.argus.recommendation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.argus.deepanalysis.DeepVerdict;
import com.argus.deepanalysis.DeepView;
import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Pure classification — every verdict must trace to an existing recommendation/deep-analysis field,
 * never a new guess (Phase 2 follow-up: "keep long-term vs. reconsider" for an existing holding). */
class HoldingOutlookTest {

	private static Recommendation recWith(RecommendationAction action, int holdDays, String thesis) {
		ProbabilityScore score = new ProbabilityScore(0.62, 0.38, 0.55, 1.8, 1.1, List.of());
		Recommendation r = new Recommendation("AAPL", score, List.of(), null, "3m");
		RecommendationPolicy.Verdict verdict = new RecommendationPolicy.Verdict(action, 70, holdDays, "3 months",
				thesis, List.of(), List.of(), null, Set.of(), List.of());
		r.applyVerdict(verdict, null);
		return r;
	}

	private static DeepView deepView(DeepVerdict verdict, boolean atRisk, String atRiskReason, String headline) {
		return new DeepView(verdict, 180, 70, headline, null, 1, atRisk, atRiskReason, null);
	}

	private static PriceGuidance.Guidance guidance(PriceGuidance.Style style) {
		return new PriceGuidance.Guidance(BigDecimal.TEN, "at market", null, "hold", BigDecimal.ONE, "stop", style);
	}

	@Test
	void noRecommendationAtAllIsNotEnoughData() {
		HoldingOutlook.Outlook out = HoldingOutlook.classify(null, null, null);

		assertEquals(HoldingOutlook.Verdict.NOT_ENOUGH_DATA, out.verdict());
		assertTrue(out.reason().contains("hasn't analyzed"), out.reason());
	}

	@Test
	void coreHoldStyleMeansKeepUsingTheDeepHeadlineAsTheReason() {
		Recommendation r = recWith(RecommendationAction.BUY, 180, "Long-term compounder thesis");
		DeepView deep = deepView(DeepVerdict.WORTH_BUYING, false, null, "Durable moat, re-accelerating growth");

		HoldingOutlook.Outlook out = HoldingOutlook.classify(r, deep, guidance(PriceGuidance.Style.CORE_HOLD));

		assertEquals(HoldingOutlook.Verdict.KEEP, out.verdict());
		assertEquals("Durable moat, re-accelerating growth", out.reason());
	}

	@Test
	void coreHoldStyleFallsBackToTheRecommendationsThesisWhenNoDeepHeadline() {
		Recommendation r = recWith(RecommendationAction.BUY, 180, "Long-term compounder thesis");

		HoldingOutlook.Outlook out = HoldingOutlook.classify(r, null, guidance(PriceGuidance.Style.CORE_HOLD));

		assertEquals(HoldingOutlook.Verdict.KEEP, out.verdict());
		assertEquals("Long-term compounder thesis", out.reason());
	}

	@Test
	void atRiskThesisIsReconsiderRegardlessOfTheHeadlineVerdict() {
		Recommendation r = recWith(RecommendationAction.BUY, 180, "thesis");
		DeepView deep = deepView(DeepVerdict.WORTH_BUYING, true, "Price has fallen through the invalidation level.", "headline");

		HoldingOutlook.Outlook out = HoldingOutlook.classify(r, deep, guidance(PriceGuidance.Style.SWING));

		assertEquals(HoldingOutlook.Verdict.RECONSIDER, out.verdict());
		assertEquals("Price has fallen through the invalidation level.", out.reason());
	}

	@Test
	void notWorthBuyingVerdictIsReconsider() {
		Recommendation r = recWith(RecommendationAction.WATCH, 30, "thesis");
		DeepView deep = deepView(DeepVerdict.NOT_WORTH_BUYING, false, null, "headline");

		HoldingOutlook.Outlook out = HoldingOutlook.classify(r, deep, null);

		assertEquals(HoldingOutlook.Verdict.RECONSIDER, out.verdict());
	}

	@Test
	void avoidActionWithNoDeepDataIsStillReconsider() {
		Recommendation r = recWith(RecommendationAction.AVOID, 30, "thesis");

		HoldingOutlook.Outlook out = HoldingOutlook.classify(r, null, null);

		assertEquals(HoldingOutlook.Verdict.RECONSIDER, out.verdict());
	}

	@Test
	void aPlainBuyThatIsNotYetCoreHoldStyleIsNotEnoughDataNotAGuess() {
		Recommendation r = recWith(RecommendationAction.BUY, 14, "thesis");

		HoldingOutlook.Outlook out = HoldingOutlook.classify(r, null, guidance(PriceGuidance.Style.SWING));

		assertEquals(HoldingOutlook.Verdict.NOT_ENOUGH_DATA, out.verdict());
	}
}
