package com.argus.recommendation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/** Explicit position-sizing rule with concentration awareness (Story 6.5, FR-15). */
class PositionSizerTest {

	private final PositionSizer sizer = new PositionSizer();

	@Test
	void bandScalesWithConfidence() {
		SizingBand low = sizer.band(0.0, 0);
		SizingBand high = sizer.band(1.0, 0);
		assertEquals(0, low.maxPercent().compareTo(new BigDecimal("1.00")));
		assertEquals(0, high.maxPercent().compareTo(new BigDecimal("3.00")));
		assertTrue(high.minPercent().compareTo(low.minPercent()) > 0);
		assertFalse(low.reduced());
	}

	@Test
	void softConcentrationHalvesAndFlags() {
		SizingBand normal = sizer.band(1.0, 0);
		SizingBand soft = sizer.band(1.0, 12.0); // ≥ 10% soft cap
		assertTrue(soft.reduced());
		assertEquals(0, soft.maxPercent().compareTo(normal.maxPercent().divide(new BigDecimal("2"))));
	}

	@Test
	void hardConcentrationZeroesTheBand() {
		SizingBand hard = sizer.band(1.0, 25.0); // ≥ 20% hard cap
		assertTrue(hard.reduced());
		assertEquals(0, hard.minPercent().compareTo(BigDecimal.ZERO));
		assertEquals(0, hard.maxPercent().compareTo(BigDecimal.ZERO));
		assertTrue(hard.reasoning().toLowerCase().contains("concentrated"));
	}

	@Test
	void confidenceIsClampedSoBandStaysBounded() {
		SizingBand over = sizer.band(5.0, 0); // confidence clamped to 1.0
		assertEquals(0, over.maxPercent().compareTo(new BigDecimal("3.00")));
	}
}
