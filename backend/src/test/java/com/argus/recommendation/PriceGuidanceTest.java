package com.argus.recommendation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.argus.deepanalysis.DeepVerdict;
import com.argus.deepanalysis.DeepView;
import com.argus.technical.ChartStudy;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/** What price to buy, sell and stop at for a human following a call — every level traced to the chart or a named house constant. */
class PriceGuidanceTest {

	private static ChartStudy chart(Double support, Double resistance, Double atrPct) {
		return new ChartStudy(300, LocalDate.now(), 100, 1.0, 2.0, 3.0, 100.0, 98.0, 90.0, ChartStudy.Trend.UPTREND, 55.0, 0.5, 0.5, atrPct,
				1.0, 1.2, 50.0, -3.0, support, resistance, List.of(), 1.0, 2.0, 0.5, "BULLISH", List.of());
	}

	@Test
	void watchAndAMissingPriceProduceNoGuidance() {
		assertNull(PriceGuidance.build(RecommendationAction.WATCH, 30, 150.0, chart(95.0, 110.0, 2.0), null, null));
		assertNull(PriceGuidance.build(RecommendationAction.BUY, 30, null, chart(95.0, 110.0, 2.0), null, null));
	}

	@Test
	void aBuyNearSupportSellsAtResistanceAndStopsBelowSupport() {
		var g = PriceGuidance.build(RecommendationAction.BUY, 30, 100.0, chart(97.0, 112.0, 2.0), null, null);

		assertEquals(0, g.buyPrice().compareTo(java.math.BigDecimal.valueOf(100.00)));
		assertTrue(g.buyNote().contains("at or near the current price"), g.buyNote());
		assertEquals(0, g.sellPrice().compareTo(java.math.BigDecimal.valueOf(112.00)));
		assertTrue(g.sellNote().contains("resistance"));
		assertTrue(g.stopPrice().doubleValue() < 100.0 && g.stopPrice().doubleValue() > 90.0);
		assertEquals(PriceGuidance.Style.SWING, g.style());
	}

	@Test
	void anExtendedBuyIsFlaggedRatherThanChased() {
		// price is 15% above support — well past the 6% "already run" threshold
		var g = PriceGuidance.build(RecommendationAction.STRONG_BUY, 30, 115.0, chart(100.0, 140.0, 2.0), null, null);

		assertTrue(g.buyNote().contains("already") && g.buyNote().contains("above support"), g.buyNote());
	}

	@Test
	void noChartLevelFallsBackToAHouseTakeProfitBandScaledByHorizon() {
		var shortCall = PriceGuidance.build(RecommendationAction.BUY, 7, 100.0, null, null, null);
		var longCall = PriceGuidance.build(RecommendationAction.BUY, 90, 100.0, null, null, "FAIR");

		assertEquals(0, shortCall.sellPrice().compareTo(java.math.BigDecimal.valueOf(108.00)));
		assertTrue(shortCall.sellNote().contains("house take-profit"));
		// 90-day + FAIR valuation qualifies as a core holding instead, so it gets no fixed sell price
		assertNull(longCall.sellPrice());
		assertEquals(PriceGuidance.Style.CORE_HOLD, longCall.style());
	}

	@Test
	void aLongHoldWithRichValuationIsNotTreatedAsACoreHolding() {
		var g = PriceGuidance.build(RecommendationAction.BUY, 90, 100.0, null, null, "RICH");

		assertEquals(PriceGuidance.Style.SWING, g.style());
		assertTrue(g.sellPrice() != null);
	}

	@Test
	void anAtRiskOrContradictedDeepVerdictAlsoRulesOutACoreHolding() {
		DeepView atRisk = new DeepView(DeepVerdict.WORTH_BUYING, 90, 70, "h", "i", 1, true, "broken", 90.0);
		DeepView contradicts = new DeepView(DeepVerdict.NOT_WORTH_BUYING, null, 70, "h", "i", 1);

		assertEquals(PriceGuidance.Style.SWING, PriceGuidance.build(RecommendationAction.BUY, 90, 100.0, null, atRisk, "CHEAP").style());
		assertEquals(PriceGuidance.Style.SWING, PriceGuidance.build(RecommendationAction.BUY, 90, 100.0, null, contradicts, "CHEAP").style());
	}

	@Test
	void aCoreHoldingMentionsAddingOnDipsTowardSupportAndNamesTheStopAsTheOnlyExit() {
		var g = PriceGuidance.build(RecommendationAction.STRONG_BUY, 90, 100.0, chart(90.0, 130.0, 2.0), null, "CHEAP");

		assertEquals(PriceGuidance.Style.CORE_HOLD, g.style());
		assertNull(g.sellPrice());
		assertTrue(g.sellNote().contains("No fixed sell target") && g.sellNote().contains("90.00") && g.sellNote().contains("Agent 11"), g.sellNote());
		assertTrue(g.stopPrice().doubleValue() < 100.0);
	}

	@Test
	void avoidGuidanceIsMirroredForTheShortSide() {
		var g = PriceGuidance.build(RecommendationAction.STRONG_AVOID, 30, 100.0, chart(85.0, 105.0, 2.0), null, null);

		assertEquals(0, g.sellPrice().compareTo(java.math.BigDecimal.valueOf(85.00)), "covers near support");
		assertTrue(g.stopPrice().doubleValue() > 100.0, "a short's stop sits above entry");
	}

	@Test
	void aSubPennyStockKeepsEnoughPrecisionInEveryLevel() {
		var g = PriceGuidance.build(RecommendationAction.BUY, 30, 0.0008, null, null, null);

		assertTrue(g.buyPrice().doubleValue() > 0);
		assertTrue(g.sellPrice().doubleValue() > g.buyPrice().doubleValue());
		assertTrue(g.stopPrice().doubleValue() > 0 && g.stopPrice().doubleValue() < g.buyPrice().doubleValue());
	}
}
