package com.argus.deepanalysis;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ThesisCheckTest {

	private static final LocalDate ANALYSED = LocalDate.of(2026, 9, 1);

	private static Optional<String> check(DeepVerdict v, Double inv, Double last, LocalDate filed, Double score, String guidance) {
		return ThesisCheck.atRisk(v, ANALYSED, inv, last, filed, score, guidance);
	}

	@Test
	void aBuyIsAtRiskOnceThePriceFallsThroughItsInvalidationLevel() {
		assertTrue(check(DeepVerdict.WORTH_BUYING, 90.0, 89.5, null, null, null).isPresent());
		assertTrue(check(DeepVerdict.WORTH_BUYING, 90.0, 90.0, null, null, null).isPresent(), "touching the level counts");
		assertTrue(check(DeepVerdict.WORTH_BUYING, 90.0, 95.0, null, null, null).isEmpty());
	}

	@Test
	void anAvoidIsAtRiskWhenThePriceRisesThroughItsLevel() {
		assertTrue(check(DeepVerdict.NOT_WORTH_BUYING, 110.0, 111.0, null, null, null).isPresent());
		assertTrue(check(DeepVerdict.NOT_WORTH_BUYING, 110.0, 105.0, null, null, null).isEmpty());
	}

	@Test
	void waitNeverHasAThesisToBreak() {
		assertTrue(check(DeepVerdict.WAIT, 90.0, 10.0, ANALYSED.plusDays(2), -1.0, "LOWERED").isEmpty());
		assertTrue(check(null, 90.0, 10.0, null, null, null).isEmpty());
	}

	@Test
	void aNegativeFilingAfterTheAnalysisUndermineABuyButOneBeforeItDoesNot() {
		assertTrue(check(DeepVerdict.WORTH_BUYING, null, 100.0, ANALYSED.plusDays(3), -0.5, "MAINTAINED").isPresent());
		assertTrue(check(DeepVerdict.WORTH_BUYING, null, 100.0, ANALYSED.minusDays(3), -0.9, "LOWERED").isEmpty(),
				"the analysis already saw a filing that predates it");
		assertTrue(check(DeepVerdict.WORTH_BUYING, null, 100.0, ANALYSED, -0.9, null).isEmpty(), "same-day filing was already in the evidence");
	}

	@Test
	void loweredGuidanceAloneUndermineABuyEvenWithAMildScore() {
		assertTrue(check(DeepVerdict.WORTH_BUYING, null, 100.0, ANALYSED.plusDays(1), -0.1, "LOWERED").get().contains("lowered guidance"));
		assertTrue(check(DeepVerdict.WORTH_BUYING, null, 100.0, ANALYSED.plusDays(1), -0.1, "MAINTAINED").isEmpty());
	}

	@Test
	void aClearlyPositiveFilingUndermineAnAvoidButAMildOneDoesNot() {
		assertTrue(check(DeepVerdict.NOT_WORTH_BUYING, null, 100.0, ANALYSED.plusDays(1), 0.5, "RAISED").isPresent());
		assertTrue(check(DeepVerdict.NOT_WORTH_BUYING, null, 100.0, ANALYSED.plusDays(1), 0.2, null).isEmpty());
	}

	@Test
	void missingDataNeverFlagsAThesis() {
		assertTrue(check(DeepVerdict.WORTH_BUYING, null, null, null, null, null).isEmpty());
	}
}
