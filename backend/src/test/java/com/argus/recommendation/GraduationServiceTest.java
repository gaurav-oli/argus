package com.argus.recommendation;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** Pure transition rules for Agent 5 graduation (Story 6.6, FR-11). */
class GraduationServiceTest {

	// evaluate(current, total, wins, rollingWins, rollingCount). rolling 50% (5/10) avoids freeze.
	private static GraduationState eval(GraduationState s, int total, int wins) {
		return GraduationService.evaluate(s, total, wins, 5, 10);
	}

	@Test
	void shadowPromotesToProbationAt20TradesAnd70Percent() {
		assertEquals(GraduationState.PROBATION, eval(GraduationState.SHADOW, 20, 14));
	}

	@Test
	void shadowStaysBelowWinRateOrTradeCount() {
		assertEquals(GraduationState.SHADOW, eval(GraduationState.SHADOW, 20, 13)); // 65%
		assertEquals(GraduationState.SHADOW, GraduationService.evaluate(GraduationState.SHADOW, 19, 19, 9, 9));
	}

	@Test
	void probationPromotesToActiveWithSustainedPerformance() {
		assertEquals(GraduationState.ACTIVE, eval(GraduationState.PROBATION, 40, 24)); // 60%
		assertEquals(GraduationState.PROBATION, eval(GraduationState.PROBATION, 40, 23)); // 57.5%
	}

	@Test
	void activeDemotesWhenRollingWinRateUnderFifty() {
		assertEquals(GraduationState.PROBATION,
				GraduationService.evaluate(GraduationState.ACTIVE, 50, 30, 4, 10)); // rolling 40%
	}

	@Test
	void activeStaysWhenRollingWinRateHealthy() {
		assertEquals(GraduationState.ACTIVE,
				GraduationService.evaluate(GraduationState.ACTIVE, 50, 30, 6, 10)); // rolling 60%
	}

	@Test
	void seriousFailurePatternFreezesFromAnyState() {
		assertEquals(GraduationState.FROZEN,
				GraduationService.evaluate(GraduationState.ACTIVE, 50, 20, 2, 10)); // rolling 20%
		assertEquals(GraduationState.FROZEN,
				GraduationService.evaluate(GraduationState.SHADOW, 15, 5, 2, 10));
	}

	@Test
	void rollingRulesRequireAFullTenTradeWindow() {
		// Only 8 trades: rolling rules (freeze/demote) don't apply yet.
		assertEquals(GraduationState.ACTIVE, GraduationService.evaluate(GraduationState.ACTIVE, 8, 1, 1, 8));
	}

	@Test
	void frozenIsTerminal() {
		assertEquals(GraduationState.FROZEN, GraduationService.evaluate(GraduationState.FROZEN, 100, 100, 10, 10));
	}

	@Test
	void badgesAndRecommendability() {
		assertEquals("UNVALIDATED", GraduationState.PROBATION.badge());
		assertEquals("FROZEN", GraduationState.FROZEN.badge());
		assertEquals(true, GraduationState.ACTIVE.canRecommend());
		assertEquals(false, GraduationState.SHADOW.canRecommend());
		assertEquals(false, GraduationState.FROZEN.canRecommend());
	}
}
