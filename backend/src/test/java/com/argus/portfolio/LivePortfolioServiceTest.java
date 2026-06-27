package com.argus.portfolio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.argus.common.LivePushService;
import com.argus.marketdata.FxRateService;
import com.argus.marketdata.MarketClock;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** Unit tests for the live valuation engine (Story 3.4) — repos/FX/push mocked, no Spring. */
class LivePortfolioServiceTest {

	private final PositionRepository positions = mock(PositionRepository.class);
	private final FxRateService fx = mock(FxRateService.class);
	private final LivePushService livePush = mock(LivePushService.class);
	// No cash in these valuation tests — a mock repo yields an empty list, so cash total is 0.
	private final CashService cash = new CashService(mock(CashBalanceRepository.class));
	private final LivePortfolioService service =
			new LivePortfolioService(positions, fx, new MarketClock(), livePush, cash);

	private static final Instant REGULAR =
			ZonedDateTime.of(LocalDate.of(2023, 6, 15), LocalTime.of(14, 0), ZoneId.of("America/New_York")).toInstant();
	private static final Instant AFTER_HOURS =
			ZonedDateTime.of(LocalDate.of(2023, 6, 15), LocalTime.of(20, 0), ZoneId.of("America/New_York")).toInstant();

	private Position aapl() {
		Position p = new Position("AAPL", "Apple", new BigDecimal("10"), new BigDecimal("1000"), "USD",
				LocalDate.of(2023, 1, 15), false, "pdf_import");
		p.updateAcbCaches(new BigDecimal("10"), new BigDecimal("1000"), "USD", new BigDecimal("1350"), false);
		return p;
	}

	private PortfolioSnapshot tickAndCapture(String ticker, String price, Instant when) {
		when(positions.findAllByOrderByTickerAsc()).thenReturn(List.of(aapl()));
		when(fx.usdCadOn(any())).thenReturn(Optional.of(new BigDecimal("1.35")));
		service.onPriceTick(ticker, new BigDecimal(price), when);
		ArgumentCaptor<PortfolioSnapshot> captor = ArgumentCaptor.forClass(PortfolioSnapshot.class);
		verify(livePush).publish(eq("/topic/portfolio"), captor.capture());
		return captor.getValue();
	}

	@Test
	void tickComputesValuePnlAndCadTotals() {
		PortfolioSnapshot snap = tickAndCapture("AAPL", "120", REGULAR);

		PositionValue pv = snap.positions().get(0);
		assertEquals(0, pv.marketValue().compareTo(new BigDecimal("1200.00")));   // 10 × 120
		assertEquals(0, pv.totalPnl().compareTo(new BigDecimal("200.00")));       // 1200 − 1000
		assertEquals(0, pv.cadMarketValue().compareTo(new BigDecimal("1620.00"))); // 1200 × 1.35
		assertEquals(0, pv.cadPnl().compareTo(new BigDecimal("270.00")));         // 1620 − 1350
		assertFalse(pv.afterHours());
		assertNull(pv.dayPnl());          // no previous close recorded → day P&L unknown
		assertNull(pv.previousClose());
		assertEquals(0, snap.totalValueCad().compareTo(new BigDecimal("1620.00")));
		assertEquals(0, snap.totalPnlCad().compareTo(new BigDecimal("270.00")));
		assertFalse(snap.anyAfterHours());
	}

	@Test
	void computesDayPnlTotalPercentAndWeight() {
		when(positions.findAllByOrderByTickerAsc()).thenReturn(List.of(aapl()));
		when(fx.usdCadOn(any())).thenReturn(Optional.of(new BigDecimal("1.35")));
		service.recordPreviousClose("AAPL", new BigDecimal("100"));
		service.onPriceTick("AAPL", new BigDecimal("120"), REGULAR);

		ArgumentCaptor<PortfolioSnapshot> captor = ArgumentCaptor.forClass(PortfolioSnapshot.class);
		verify(livePush).publish(eq("/topic/portfolio"), captor.capture());
		PositionValue pv = captor.getValue().positions().get(0);

		assertEquals(0, pv.dayPnl().compareTo(new BigDecimal("200.00")));        // (120−100) × 10
		assertEquals(0, pv.dayPnlPercent().compareTo(new BigDecimal("20.00")));  // (120−100)/100
		assertEquals(0, pv.totalPnlPercent().compareTo(new BigDecimal("20.00"))); // 200/1000
		assertEquals(0, pv.weightPercent().compareTo(new BigDecimal("100.00"))); // single priced holding
	}

	@Test
	void weightsSumTo100AcrossPricedPositionsIncludingFxEstimated() {
		Position aapl = aapl(); // cadAcb 1350, USD, 10 sh
		Position tsla = new Position("TSLA", null, new BigDecimal("5"), new BigDecimal("400"), "USD", null, false, "manual");
		tsla.updateAcbCaches(new BigDecimal("5"), new BigDecimal("400"), "USD", null, true); // FX-estimated → cadAcb null
		when(positions.findAllByOrderByTickerAsc()).thenReturn(List.of(aapl, tsla));
		when(fx.usdCadOn(any())).thenReturn(Optional.of(new BigDecimal("1.35")));

		service.onPriceTick("AAPL", new BigDecimal("120"), REGULAR); // cadMv 1620
		service.onPriceTick("TSLA", new BigDecimal("100"), REGULAR); // cadMv 675 (still priced though FX-estimated)

		ArgumentCaptor<PortfolioSnapshot> captor = ArgumentCaptor.forClass(PortfolioSnapshot.class);
		verify(livePush, atLeastOnce()).publish(eq("/topic/portfolio"), captor.capture());
		PortfolioSnapshot snap = captor.getValue();

		assertEquals(0, snap.totalValueCad().compareTo(new BigDecimal("2295.00"))); // 1620 + 675 — includes FX-estimated
		double weightSum = snap.positions().stream()
				.filter(p -> p.weightPercent() != null)
				.mapToDouble(p -> p.weightPercent().doubleValue())
				.sum();
		assertEquals(100.0, weightSum, 0.05); // weights sum to ~100%, not >100%
	}

	@Test
	void afterHoursTickIsFlagged() {
		PortfolioSnapshot snap = tickAndCapture("AAPL", "120", AFTER_HOURS);
		assertTrue(snap.positions().get(0).afterHours());
		assertTrue(snap.anyAfterHours());
	}

	@Test
	void tickForNonHeldTickerLeavesHoldingUnpriced() {
		PortfolioSnapshot snap = tickAndCapture("ZZZ", "5", REGULAR);
		PositionValue pv = snap.positions().get(0);
		assertEquals("AAPL", pv.ticker());
		assertNull(pv.price());
		assertNull(pv.marketValue());
		assertEquals(0, snap.totalValueCad().compareTo(BigDecimal.ZERO)); // nothing priced yet
	}
}
