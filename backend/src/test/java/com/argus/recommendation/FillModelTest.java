package com.argus.recommendation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class FillModelTest {

	@Test
	void cheapVolatileStocksCostMore() {
		assertTrue(FillModel.roundTripPct(5, 6.0) > FillModel.roundTripPct(200, 1.5));
	}

	@Test
	void slippageIsCapped() {
		assertEquals(2 * (FillModel.COMMISSION_PCT + 0.02 + FillModel.MAX_SLIPPAGE_PCT), FillModel.roundTripPct(100, 50.0), 1e-9);
	}

	@Test
	void costComesOffTheReturnAndCanTurnAWinIntoALoss() {
		SimulatedTrade t = new SimulatedTrade(1L, "X", SignalDirection.BULLISH, BigDecimal.valueOf(100),
				BigDecimal.valueOf(100), 30, BigDecimal.valueOf(500));
		t.applyCosts(0.5);
		t.close(BigDecimal.valueOf(100.3), BigDecimal.valueOf(500));
		assertEquals(-0.2, t.getReturnPct().doubleValue(), 1e-9);
		assertEquals(-0.2, t.getExcessReturnPct().doubleValue(), 1e-9);
		assertTrue(!t.getWon());
	}
}
