package com.argus.portfolio;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/** Unit tests for lot-level corporate-action math (Story 3.3) — no Spring context. */
class PositionLotTest {

	private static PositionLot lot(String shares, String cost) {
		return new PositionLot(1L, new BigDecimal(shares), new BigDecimal(cost), "USD",
				LocalDate.of(2023, 1, 15), new BigDecimal("1.35"), false);
	}

	@Test
	void splitScalesSharesAndPreservesTotalCost() {
		PositionLot l = lot("100", "150.25");
		l.applySplit(new BigDecimal("2")); // 2:1 split
		assertEquals(0, l.getShares().compareTo(new BigDecimal("200")));
		assertEquals(0, l.getTotalCost().compareTo(new BigDecimal("150.25"))); // total cost unchanged
	}

	@Test
	void reverseSplitScalesSharesDown() {
		PositionLot l = lot("100", "150.25");
		l.applySplit(new BigDecimal("0.1")); // 1:10 reverse
		assertEquals(0, l.getShares().compareTo(new BigDecimal("10")));
		assertEquals(0, l.getTotalCost().compareTo(new BigDecimal("150.25")));
	}
}
