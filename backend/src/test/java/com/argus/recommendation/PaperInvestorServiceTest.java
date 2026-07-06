package com.argus.recommendation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.argus.model.ModelGateway;
import com.argus.portfolio.LivePortfolioService;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The Investor persona's paper-trading loop (FR-11 follow-up): opening simulated positions, marking
 * them to market on the direction-adjusted return, and feeding win/loss into graduation autonomously.
 */
class PaperInvestorServiceTest {

	private final SimulatedTradeRepository trades = mock(SimulatedTradeRepository.class);
	private final LivePortfolioService prices = mock(LivePortfolioService.class);
	private final GraduationService graduation = mock(GraduationService.class);
	private final ModelGateway gateway = mock(ModelGateway.class);

	// horizon 0 → an opened trade is immediately due for the close pass.
	private final PaperInvestorService investor = new PaperInvestorService(
			trades, prices, graduation, gateway, new BigDecimal("100"), 0);

	private static Recommendation rec(String ticker, SignalDirection dir, long id) {
		Recommendation r = mock(Recommendation.class);
		when(r.getId()).thenReturn(id);
		when(r.getTicker()).thenReturn(ticker);
		when(r.getDirection()).thenReturn(dir);
		return r;
	}

	// ---- entity math: direction-adjusted return ----

	@Test
	void bullishWinsWhenPriceRises() {
		SimulatedTrade t = new SimulatedTrade(1L, "AAPL", SignalDirection.BULLISH, bd(100), bd(50), 30);
		t.close(bd(55)); // +10%
		assertEquals(0, t.getReturnPct().compareTo(bd(10)));
		assertTrue(t.getWon());
	}

	@Test
	void bearishWinsWhenPriceFalls() {
		SimulatedTrade t = new SimulatedTrade(1L, "TSLA", SignalDirection.BEARISH, bd(100), bd(50), 30);
		t.close(bd(45)); // price -10% → a bearish call gains
		assertEquals(0, t.getReturnPct().compareTo(bd(10)));
		assertTrue(t.getWon());
	}

	@Test
	void bullishLosesWhenPriceFalls() {
		SimulatedTrade t = new SimulatedTrade(1L, "AAPL", SignalDirection.BULLISH, bd(100), bd(50), 30);
		t.close(bd(48));
		assertFalse(t.getWon());
	}

	// ---- open ----

	@Test
	void opensPricedDirectionalRecommendation() {
		when(trades.existsByRecommendationId(7L)).thenReturn(false);
		when(prices.latestPrice("AAPL")).thenReturn(Optional.of(bd(50)));
		when(trades.save(any())).thenAnswer(i -> i.getArgument(0));

		Optional<SimulatedTrade> opened = investor.open(rec("AAPL", SignalDirection.BULLISH, 7L));

		assertTrue(opened.isPresent());
		assertEquals(0, opened.get().getShares().compareTo(bd(2))); // $100 / $50
	}

	@Test
	void skipsNeutralUnpricedAndDuplicate() {
		// neutral
		assertTrue(investor.open(rec("AAPL", SignalDirection.NEUTRAL, 1L)).isEmpty());
		// unpriced
		when(prices.latestPrice("XYZ")).thenReturn(Optional.empty());
		assertTrue(investor.open(rec("XYZ", SignalDirection.BULLISH, 2L)).isEmpty());
		// duplicate
		when(trades.existsByRecommendationId(3L)).thenReturn(true);
		assertTrue(investor.open(rec("AAPL", SignalDirection.BULLISH, 3L)).isEmpty());
		verify(trades, never()).save(any());
	}

	// ---- close loop feeds graduation ----

	@Test
	void closesDueTradeAndRecordsWinToGraduation() {
		SimulatedTrade open = new SimulatedTrade(9L, "AAPL", SignalDirection.BULLISH, bd(100), bd(50), 0);
		when(trades.findByStatus(SimulatedTrade.Status.OPEN)).thenReturn(List.of(open));
		when(prices.latestPrice("AAPL")).thenReturn(Optional.of(bd(60))); // +20%

		investor.closeDueTrades();

		assertEquals(SimulatedTrade.Status.CLOSED, open.getStatus());
		verify(graduation).recordOutcome(eq(true), eq(9L));
	}

	@Test
	void losingCloseRecordsPostMortemAndLoss() {
		SimulatedTrade open = new SimulatedTrade(9L, "AAPL", SignalDirection.BULLISH, bd(100), bd(50), 0);
		when(trades.findByStatus(SimulatedTrade.Status.OPEN)).thenReturn(List.of(open));
		when(prices.latestPrice("AAPL")).thenReturn(Optional.of(bd(40))); // -20%
		when(gateway.generate(anyString())).thenReturn("Momentum faded; overweighted social buzz.");

		investor.closeDueTrades();

		verify(graduation).recordOutcome(eq(false), eq(9L));
		assertEquals("Momentum faded; overweighted social buzz.", open.getReview());
	}

	private static BigDecimal bd(long v) {
		return BigDecimal.valueOf(v);
	}
}
