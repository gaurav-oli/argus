package com.argus.deepanalysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.argus.deepanalysis.InvalidationLevel.Resolved;
import org.junit.jupiter.api.Test;

/** A model-invented invalidation price is only trusted when it makes sense against the actual price. */
class InvalidationLevelTest {

	@Test
	void aSensibleModelLevelBelowThePriceIsKeptForABuy() {
		Resolved r = InvalidationLevel.resolve(DeepVerdict.WORTH_BUYING, 92.0, 100.0, 95.0, 110.0, 2.0);

		assertEquals(92.0, r.price());
		assertTrue(r.modelLevelUsed());
	}

	@Test
	void aLevelAboveThePriceForABuyIsNonsenseAndIsReplacedByAChartLevel() {
		Resolved r = InvalidationLevel.resolve(DeepVerdict.WORTH_BUYING, 105.0, 100.0, null, null, 2.0);

		assertFalse(r.modelLevelUsed());
		assertEquals(95.0, r.price(), 0.01, "2.5 × 2% ATR = 5% is clamped to the 5% floor");
	}

	@Test
	void aLevelAbsurdlyFarAwayIsReplaced() {
		Resolved r = InvalidationLevel.resolve(DeepVerdict.WORTH_BUYING, 30.0, 100.0, null, null, 4.0);

		assertFalse(r.modelLevelUsed());
		assertEquals(90.0, r.price(), 0.01, "2.5 × 4% = 10%");
	}

	@Test
	void aLevelRightOnThePriceIsTooTightToBeAStop() {
		assertFalse(InvalidationLevel.resolve(DeepVerdict.WORTH_BUYING, 99.5, 100.0, null, null, 2.0).modelLevelUsed());
	}

	@Test
	void theFallbackSitsJustBelowSupportWhenSupportIsCloserThanTheAtrLevel() {
		Resolved r = InvalidationLevel.resolve(DeepVerdict.WORTH_BUYING, null, 100.0, 94.0, 120.0, 5.0);

		assertEquals(94.0 * 0.99, r.price(), 0.01);
	}

	@Test
	void anAvoidIsInvalidatedByARiseNotAFall() {
		Resolved ok = InvalidationLevel.resolve(DeepVerdict.NOT_WORTH_BUYING, 108.0, 100.0, null, null, 2.0);
		Resolved bad = InvalidationLevel.resolve(DeepVerdict.NOT_WORTH_BUYING, 90.0, 100.0, null, null, 2.0);

		assertEquals(108.0, ok.price());
		assertTrue(bad.price() > 100.0);
	}

	@Test
	void aSubPennyStockDoesNotRoundItsInvalidationLevelToZero() {
		// IDEXQ-shaped bug: a flat 2-decimal round floored a $0.0008 stock's level to $0.00, which every
		// live price is "through" — the thesis tracker then flagged it AT_RISK every single hour, forever.
		Resolved r = InvalidationLevel.resolve(DeepVerdict.NOT_WORTH_BUYING, null, 0.0008, null, null, 5.0);

		assertTrue(r.price() > 0, "must not floor to zero");
		assertTrue(r.price() > 0.0008, "an avoid is invalidated by a rise, so the level sits above the price");
	}

	@Test
	void waitHasNothingToInvalidateAndAMissingPriceCannotBeValidated() {
		assertNull(InvalidationLevel.resolve(DeepVerdict.WAIT, 90.0, 100.0, null, null, 2.0).price());
		assertNull(InvalidationLevel.resolve(DeepVerdict.WORTH_BUYING, 90.0, null, null, null, 2.0).price());
	}
}
