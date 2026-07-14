package com.argus.recommendation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.argus.marketdata.BenchmarkPriceSource;
import com.argus.model.ModelGateway;
import com.argus.portfolio.LivePortfolioService;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The Investor persona's paper-trading loop (FR-11 follow-up + Fable 5 review): staggered per-horizon
 * legs with thesis-level dedup and re-affirmation, marking to market on the direction-adjusted return
 * <em>in excess of SPY</em> (absolute fallback when unbenchmarked), and feeding win/loss into
 * graduation autonomously.
 */
class PaperInvestorServiceTest {

	private final SimulatedTradeRepository trades = mock(SimulatedTradeRepository.class);
	private final LivePortfolioService prices = mock(LivePortfolioService.class);
	private final BenchmarkPriceSource benchmark = mock(BenchmarkPriceSource.class);
	private final GraduationService graduation = mock(GraduationService.class);
	private final TradeConfirmationService confirmations = mock(TradeConfirmationService.class);
	private final ModelGateway gateway = mock(ModelGateway.class);

	// Default horizons (7/30/90); the close tests construct their own horizon-0 trades so they are
	// immediately due. Benchmark is absent unless a test sets it.
	private final PaperInvestorService investor = new PaperInvestorService(
			trades, prices, benchmark, graduation, confirmations, gateway, new BigDecimal("100"), "", 0);

	private PaperInvestorService staggeredInvestor() {
		return new PaperInvestorService(trades, prices, benchmark, graduation, confirmations, gateway,
				new BigDecimal("100"), "7,30,90", 0);
	}

	private static Recommendation rec(String ticker, SignalDirection dir, long id) {
		Recommendation r = mock(Recommendation.class);
		when(r.getId()).thenReturn(id);
		when(r.getTicker()).thenReturn(ticker);
		when(r.getDirection()).thenReturn(dir);
		return r;
	}

	// ---- entity math: direction-adjusted return, benchmark-relative wins ----

	@Test
	void bullishWinsWhenPriceRises_unbenchmarked() {
		SimulatedTrade t = new SimulatedTrade(1L, "AAPL", SignalDirection.BULLISH, bd(100), bd(50), 30, null);
		t.close(bd(55), null); // +10%, no benchmark → absolute
		assertEquals(0, t.getReturnPct().compareTo(bd(10)));
		assertNull(t.getExcessReturnPct());
		assertTrue(t.getWon());
	}

	@Test
	void bearishWinsWhenPriceFalls_unbenchmarked() {
		SimulatedTrade t = new SimulatedTrade(1L, "TSLA", SignalDirection.BEARISH, bd(100), bd(50), 30, null);
		t.close(bd(45), null); // price -10% → a bearish call gains
		assertEquals(0, t.getReturnPct().compareTo(bd(10)));
		assertTrue(t.getWon());
	}

	@Test
	void bullishUpMoveStillLosesWhenItLagsSpy() {
		// Stock +2% but SPY +5% over the window: absolutely up, relatively a bad call → LOST.
		SimulatedTrade t = new SimulatedTrade(1L, "AAPL", SignalDirection.BULLISH, bd(100), bd(100), 30, bd(500));
		t.close(bd(102), bd(525));
		assertEquals(0, t.getReturnPct().compareTo(bd(2)));
		assertEquals(0, t.getExcessReturnPct().compareTo(bd(-3)));
		assertFalse(t.getWon());
	}

	@Test
	void bearishWinsWhenStockLagsSpyEvenIfPriceRose() {
		// Stock +1% while SPY +5%: the bearish (underperform) call was right vs the market.
		SimulatedTrade t = new SimulatedTrade(1L, "QS", SignalDirection.BEARISH, bd(100), bd(100), 30, bd(500));
		t.close(bd(101), bd(525));
		assertEquals(0, t.getExcessReturnPct().compareTo(bd(4))); // -(1% − 5%)
		assertTrue(t.getWon());
	}

	@Test
	void reaffirmIncrements() {
		SimulatedTrade t = new SimulatedTrade(1L, "AAPL", SignalDirection.BULLISH, bd(100), bd(50), 30, null);
		assertEquals(0, t.getReaffirmations());
		t.reaffirm();
		t.reaffirm();
		assertEquals(2, t.getReaffirmations());
	}

	// ---- open: staggered legs + thesis dedup + re-affirmation ----

	@Test
	void opensOneLegPerHorizonWithBenchmark() {
		when(trades.existsByRecommendationId(7L)).thenReturn(false);
		when(prices.latestPrice("AAPL")).thenReturn(Optional.of(bd(50)));
		when(benchmark.latest()).thenReturn(Optional.of(bd(500)));
		when(trades.save(any())).thenAnswer(i -> i.getArgument(0));

		List<SimulatedTrade> opened = staggeredInvestor().open(rec("AAPL", SignalDirection.BULLISH, 7L));

		assertEquals(3, opened.size());
		assertEquals(List.of(7, 30, 90), opened.stream().map(SimulatedTrade::getHorizonDays).toList());
		for (SimulatedTrade t : opened) {
			assertEquals(0, t.getShares().compareTo(bd(2))); // $100 / $50
			assertEquals(0, t.getBenchmarkEntry().compareTo(bd(500)));
		}
	}

	@Test
	void skipsHorizonsAlreadyOpenForTheThesis() {
		when(trades.existsByRecommendationId(8L)).thenReturn(false);
		when(prices.latestPrice("AAPL")).thenReturn(Optional.of(bd(50)));
		when(benchmark.latest()).thenReturn(Optional.empty());
		// 7d leg already open; 30/90 free.
		when(trades.existsByTickerAndDirectionAndHorizonDaysAndStatus(
				"AAPL", SignalDirection.BULLISH, 7, SimulatedTrade.Status.OPEN)).thenReturn(true);
		when(trades.save(any())).thenAnswer(i -> i.getArgument(0));

		List<SimulatedTrade> opened = staggeredInvestor().open(rec("AAPL", SignalDirection.BULLISH, 8L));

		assertEquals(List.of(30, 90), opened.stream().map(SimulatedTrade::getHorizonDays).toList());
	}

	@Test
	void reaffirmsInsteadOfDuplicatingWhenAllHorizonsOpen() {
		when(trades.existsByRecommendationId(9L)).thenReturn(false);
		when(prices.latestPrice("AAPL")).thenReturn(Optional.of(bd(50)));
		when(benchmark.latest()).thenReturn(Optional.empty());
		when(trades.existsByTickerAndDirectionAndHorizonDaysAndStatus(
				eq("AAPL"), eq(SignalDirection.BULLISH), anyInt(), eq(SimulatedTrade.Status.OPEN)))
				.thenReturn(true);
		SimulatedTrade leg = new SimulatedTrade(1L, "AAPL", SignalDirection.BULLISH, bd(100), bd(50), 7, null);
		when(trades.findByTickerAndDirectionAndStatus("AAPL", SignalDirection.BULLISH,
				SimulatedTrade.Status.OPEN)).thenReturn(List.of(leg));

		List<SimulatedTrade> opened = staggeredInvestor().open(rec("AAPL", SignalDirection.BULLISH, 9L));

		assertTrue(opened.isEmpty());
		assertEquals(1, leg.getReaffirmations());
		verify(trades).saveAll(List.of(leg));
	}

	@Test
	void skipsNeutralUnpricedAndDuplicate() {
		// neutral
		assertTrue(investor.open(rec("AAPL", SignalDirection.NEUTRAL, 1L)).isEmpty());
		// unpriced
		when(prices.latestPrice("XYZ")).thenReturn(Optional.empty());
		assertTrue(investor.open(rec("XYZ", SignalDirection.BULLISH, 2L)).isEmpty());
		// duplicate recommendation
		when(trades.existsByRecommendationId(3L)).thenReturn(true);
		assertTrue(investor.open(rec("AAPL", SignalDirection.BULLISH, 3L)).isEmpty());
		verify(trades, never()).save(any());
	}

	// ---- close loop feeds graduation ----

	@Test
	void closesDueTradeAndRecordsWinToGraduation() {
		SimulatedTrade open = new SimulatedTrade(9L, "AAPL", SignalDirection.BULLISH, bd(100), bd(50), 0, null);
		when(trades.findByStatus(SimulatedTrade.Status.OPEN)).thenReturn(List.of(open));
		when(prices.latestPrice("AAPL")).thenReturn(Optional.of(bd(60))); // +20%
		when(benchmark.latest()).thenReturn(Optional.empty());

		investor.closeDueTrades();

		assertEquals(SimulatedTrade.Status.CLOSED, open.getStatus());
		verify(graduation).recordOutcome(eq(true), eq(9L));
		// The user's Taken/Declined decision (if any) gets the realized outcome — regret analysis.
		verify(confirmations).recordOutcomeFromPaperTrade(eq(9L), eq(true));
	}

	@Test
	void benchmarkedCloseDecidesWinOnExcessReturn() {
		// Stock +20% but entry captured SPY 500 and the close pass sees SPY 650 (+30%): LOST vs market.
		SimulatedTrade open = new SimulatedTrade(9L, "AAPL", SignalDirection.BULLISH, bd(100), bd(50), 0, bd(500));
		when(trades.findByStatus(SimulatedTrade.Status.OPEN)).thenReturn(List.of(open));
		when(prices.latestPrice("AAPL")).thenReturn(Optional.of(bd(60)));
		when(benchmark.latest()).thenReturn(Optional.of(bd(650)));
		when(gateway.generate(anyString())).thenReturn("Beta, not alpha.");

		investor.closeDueTrades();

		assertEquals(0, open.getExcessReturnPct().compareTo(bd(-10)));
		verify(graduation).recordOutcome(eq(false), eq(9L));
	}

	@Test
	void losingCloseRecordsPostMortemAndLoss() {
		SimulatedTrade open = new SimulatedTrade(9L, "AAPL", SignalDirection.BULLISH, bd(100), bd(50), 0, null);
		when(trades.findByStatus(SimulatedTrade.Status.OPEN)).thenReturn(List.of(open));
		when(prices.latestPrice("AAPL")).thenReturn(Optional.of(bd(40))); // -20%
		when(benchmark.latest()).thenReturn(Optional.empty());
		when(gateway.generate(anyString())).thenReturn("Momentum faded; overweighted social buzz.");

		investor.closeDueTrades();

		verify(graduation).recordOutcome(eq(false), eq(9L));
		assertEquals("Momentum faded; overweighted social buzz.", open.getReview());
	}

	private static BigDecimal bd(long v) {
		return BigDecimal.valueOf(v);
	}
}
