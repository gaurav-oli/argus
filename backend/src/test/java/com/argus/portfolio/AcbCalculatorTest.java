package com.argus.portfolio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Unit tests for weighted-average ACB (Story 3.2, FR-1b / A-16) — no Spring context. */
class AcbCalculatorTest {

	private final AcbCalculator calc = new AcbCalculator();

	private static PositionLot lot(String shares, String cost, String ccy, String fx, boolean estimated) {
		return new PositionLot(1L, new BigDecimal(shares), cost == null ? null : new BigDecimal(cost),
				ccy, LocalDate.of(2023, 1, 15), fx == null ? null : new BigDecimal(fx), estimated);
	}

	@Test
	void usdLotWithKnownFxComputesCadAcb() {
		AcbCalculator.Acb acb = calc.compute(List.of(lot("100", "150.25", "USD", "1.35", false)));
		assertEquals(0, acb.shares().compareTo(new BigDecimal("100")));
		assertEquals(0, acb.costBasis().compareTo(new BigDecimal("150.25")));
		assertEquals("USD", acb.currency());
		assertEquals(0, acb.cadAcb().compareTo(new BigDecimal("202.8375"))); // 150.25 * 1.35
		assertFalse(acb.fxEstimated());
	}

	@Test
	void weightedAverageAcrossMultipleLots() {
		AcbCalculator.Acb acb = calc.compute(List.of(
				lot("10", "1000.00", "USD", "1.30", false),
				lot("20", "3000.00", "USD", "1.40", false)));
		assertEquals(0, acb.shares().compareTo(new BigDecimal("30")));
		assertEquals(0, acb.costBasis().compareTo(new BigDecimal("4000.00")));
		// CAD ACB = 1000*1.30 + 3000*1.40 = 1300 + 4200 = 5500
		assertEquals(0, acb.cadAcb().compareTo(new BigDecimal("5500.0000")));
		assertFalse(acb.fxEstimated());
	}

	@Test
	void cadLotUsesFxOne() {
		AcbCalculator.Acb acb = calc.compute(List.of(lot("100", "8000.00", "CAD", "1", false)));
		assertEquals(0, acb.cadAcb().compareTo(new BigDecimal("8000.00")));
		assertFalse(acb.fxEstimated());
	}

	@Test
	void lotWithUnknownFxIsEstimatedAndHasNoCadAcb() {
		AcbCalculator.Acb acb = calc.compute(List.of(lot("10", "500.00", "USD", null, true)));
		assertEquals(0, acb.costBasis().compareTo(new BigDecimal("500.00")));
		assertNull(acb.cadAcb());
		assertTrue(acb.fxEstimated());
	}
}
