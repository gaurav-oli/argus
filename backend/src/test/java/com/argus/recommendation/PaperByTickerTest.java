package com.argus.recommendation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class PaperByTickerTest {

	private static PaperInvestorService.LedgerRow open(String ticker, String amount, String unrealizedPct) {
		return new PaperInvestorService.LedgerRow(1, ticker, "BULLISH", "CURRENT", "OPEN", Instant.parse("2026-10-01T14:00:00Z"),
				BigDecimal.TEN, BigDecimal.TEN, new BigDecimal(amount), null, null, null, null, null, 3L, null, null, null, null,
				false, false, null, unrealizedPct == null ? null : new BigDecimal(unrealizedPct), null, null);
	}

	private static PaperInvestorService.LedgerRow closed(String ticker, boolean won, String pnl, String closedAt) {
		return new PaperInvestorService.LedgerRow(2, ticker, "BULLISH", "CURRENT", "CLOSED", Instant.parse("2026-09-01T14:00:00Z"),
				BigDecimal.TEN, BigDecimal.TEN, new BigDecimal("100"), null, null, Instant.parse(closedAt), BigDecimal.ONE,
				"HORIZON", 30L, null, new BigDecimal(pnl), null, won, false, false, null, null, null, null);
	}

	@Test
	void foldsOpenLegsIntoAWeightedLivePnl() {
		List<PaperByTicker.View> v = PaperByTicker.fold(List.of(open("AAPL", "100", "10"), open("AAPL", "50", "-4")));
		PaperByTicker.View a = v.get(0);
		assertEquals("BULLISH", a.openDirection());
		assertEquals(2, a.openLegs());
		assertEquals(0, a.openAmount().compareTo(new BigDecimal("150.00")));
		assertEquals(0, a.unrealizedPnl().compareTo(new BigDecimal("8.00")), "+$10 − $2");
		assertEquals(0, a.unrealizedPct().compareTo(new BigDecimal("5.33")));
		assertEquals(0, a.closedTrades());
		assertNull(a.realizedPnl());
	}

	@Test
	void foldsThePastRecordAndTheLastResult() {
		List<PaperByTicker.View> v = PaperByTicker.fold(List.of(closed("MSFT", true, "4.20", "2026-09-10T20:00:00Z"),
				closed("MSFT", false, "-3.00", "2026-09-20T20:00:00Z"), open("AAPL", "100", null)));
		PaperByTicker.View aapl = v.get(0);
		PaperByTicker.View msft = v.get(1);
		assertNull(aapl.unrealizedPct(), "unpriced open leg has no live P&L");
		assertNull(msft.openDirection());
		assertEquals(2, msft.closedTrades());
		assertEquals(1, msft.wins());
		assertEquals(0, msft.realizedPnl().compareTo(new BigDecimal("1.20")));
		assertEquals("LOST", msft.lastResult());
	}
}
