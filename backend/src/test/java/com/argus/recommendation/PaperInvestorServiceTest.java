package com.argus.recommendation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.argus.deepanalysis.DeepAnalysis;
import com.argus.deepanalysis.DeepAnalysisService;
import com.argus.deepanalysis.DeepVerdict;
import com.argus.learning.LessonEffect;
import com.argus.learning.Lessons;
import com.argus.marketdata.BenchmarkPriceSource;
import com.argus.model.ModelGateway;
import com.argus.portfolio.LivePortfolioService;
import com.argus.technical.ChartStudy;
import com.argus.technical.ChartStudyService;
import java.time.Duration;
import java.time.LocalDate;
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
	private final com.argus.regime.SectorClassifier sectors = mock(com.argus.regime.SectorClassifier.class);
	private final Lessons lessons = mock(Lessons.class);
	private final ChartStudyService charts = mock(ChartStudyService.class);
	private final DeepAnalysisService deepAnalyses = mock(DeepAnalysisService.class);
	private final RecommendationRepository recommendationRepo = mock(RecommendationRepository.class);

	{
		when(lessons.evaluate(any())).thenReturn(LessonEffect.none());
	}

	// Default horizons (7/30/90); the close tests construct their own horizon-0 trades so they are
	// immediately due. Benchmark is absent unless a test sets it.
	private final PaperInvestorService investor = new PaperInvestorService(
			trades, prices, benchmark, graduation, confirmations, gateway, new BigDecimal("100"), "", 0, sectors, 4, lessons, charts, deepAnalyses, recommendationRepo);

	private PaperInvestorService staggeredInvestor() {
		return new PaperInvestorService(trades, prices, benchmark, graduation, confirmations, gateway,
				new BigDecimal("100"), "7,30,90", 0, sectors, 4, lessons, charts, deepAnalyses, recommendationRepo);
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

	// ---- open() is also where the Investor's Taken decision gets recorded (Trade Journal, regret
	// analysis) — opening/re-affirming a position is taking the call. Recommendation's direction is
	// always a binary bull/bear call in practice (never NEUTRAL), so there's no agent-driven Declined
	// path today; NEUTRAL here is only exercising the defensive branch. ----

	@Test
	void neutralCallRecordsNoDecision() {
		investor.open(rec("AAPL", SignalDirection.NEUTRAL, 1L));
		verify(confirmations, never()).recordAgentDecision(any(), any());
	}

	@Test
	void unpricedTickerRecordsNoDecision() {
		// An infra gap (no live price), not a real decision — must not be logged as either taken or declined.
		when(prices.latestPrice("XYZ")).thenReturn(Optional.empty());
		investor.open(rec("XYZ", SignalDirection.BULLISH, 2L));
		verify(confirmations, never()).recordAgentDecision(any(), any());
	}

	@Test
	void duplicateRecommendationRecordsNoDecision() {
		// Idempotency guard, not a new decision — recordAgentDecision itself is separately idempotent,
		// but this path shouldn't even attempt it.
		when(trades.existsByRecommendationId(3L)).thenReturn(true);
		investor.open(rec("AAPL", SignalDirection.BULLISH, 3L));
		verify(confirmations, never()).recordAgentDecision(any(), any());
	}

	@Test
	void openingNewLegsRecordsAgentTaken() {
		when(trades.existsByRecommendationId(7L)).thenReturn(false);
		when(prices.latestPrice("AAPL")).thenReturn(Optional.of(bd(50)));
		when(benchmark.latest()).thenReturn(Optional.of(bd(500)));
		when(trades.save(any())).thenAnswer(i -> i.getArgument(0));

		staggeredInvestor().open(rec("AAPL", SignalDirection.BULLISH, 7L));

		verify(confirmations).recordAgentDecision(7L, TradeDecision.Decision.TAKEN);
	}

	@Test
	void reaffirmingAnExistingThesisAlsoRecordsAgentTaken() {
		// Re-affirming still means the Investor holds (and is restating) a real position — TAKEN, same
		// as opening a brand-new leg.
		when(trades.existsByRecommendationId(9L)).thenReturn(false);
		when(prices.latestPrice("AAPL")).thenReturn(Optional.of(bd(50)));
		when(benchmark.latest()).thenReturn(Optional.empty());
		when(trades.existsByTickerAndDirectionAndHorizonDaysAndStatus(
				eq("AAPL"), eq(SignalDirection.BULLISH), anyInt(), eq(SimulatedTrade.Status.OPEN)))
				.thenReturn(true);
		SimulatedTrade leg = new SimulatedTrade(1L, "AAPL", SignalDirection.BULLISH, bd(100), bd(50), 7, null);
		when(trades.findByTickerAndDirectionAndStatus("AAPL", SignalDirection.BULLISH,
				SimulatedTrade.Status.OPEN)).thenReturn(List.of(leg));

		staggeredInvestor().open(rec("AAPL", SignalDirection.BULLISH, 9L));

		verify(confirmations).recordAgentDecision(9L, TradeDecision.Decision.TAKEN);
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

	// ---- Agent 10 gives every trade a chart-based protective stop ----

	private static ChartStudy chartWith(Double atrPct, Double support, Double resistance) {
		return new ChartStudy(300, LocalDate.now(), 100, 1.0, 2.0, 3.0, 100.0, 98.0, 90.0, ChartStudy.Trend.UPTREND, 55.0, 0.5, 0.5, atrPct,
				1.0, 1.0, 50.0, -3.0, support, resistance, List.of(), 1.0, 2.0, 0.4, "BULLISH", List.of());
	}

	private double stop(SignalDirection dir, ChartStudy chart) {
		when(charts.studyFor("AAPL")).thenReturn(Optional.ofNullable(chart));
		return investor.stopFor(dir, "AAPL", bd(100)).doubleValue();
	}

	@Test
	void theStopIsTwoAndAHalfAtrsClampedToFiveToFifteenPercent() {
		assertEquals(95.0, stop(SignalDirection.BULLISH, chartWith(2.0, null, null)), 1e-6, "2.5 × 2% = 5%");
		assertEquals(95.0, stop(SignalDirection.BULLISH, chartWith(1.0, null, null)), 1e-6, "tiny ATR still gets a 5% floor");
		assertEquals(85.0, stop(SignalDirection.BULLISH, chartWith(8.0, null, null)), 1e-6, "huge ATR is capped at 15%");
		assertEquals(110.0, stop(SignalDirection.BEARISH, chartWith(4.0, null, null)), 1e-6, "a short's stop is above entry");
	}

	@Test
	void theStopTightensToNearbySupportForALongAndResistanceForAShort() {
		assertEquals(93.06, stop(SignalDirection.BULLISH, chartWith(4.0, 94.0, null)), 1e-6, "just below support at 94");
		assertEquals(90.0, stop(SignalDirection.BULLISH, chartWith(4.0, 99.0, null)), 1e-6, "support closer than 3% is too tight to use");
		assertEquals(107.06, stop(SignalDirection.BEARISH, chartWith(4.0, null, 106.0)), 1e-6);
	}

	@Test
	void withNoChartHistoryTheStopIsAFlatTenPercent() {
		assertEquals(90.0, stop(SignalDirection.BULLISH, null), 1e-6);
		assertEquals(110.0, stop(SignalDirection.BEARISH, null), 1e-6);
	}

	@Test
	void openingATradeStoresItsStop() {
		when(trades.existsByRecommendationId(7L)).thenReturn(false);
		when(prices.latestPrice("AAPL")).thenReturn(Optional.of(bd(100)));
		when(benchmark.latest()).thenReturn(Optional.empty());
		when(trades.save(any())).thenAnswer(i -> i.getArgument(0));

		SimulatedTrade leg = investor.open(rec("AAPL", SignalDirection.BULLISH, 7L)).get(0);

		assertEquals(0, leg.getStopPrice().compareTo(new BigDecimal("90.000000")));
	}

	// ---- learned lessons shape the next trade ----

	@Test
	void aLessonSizeMultiplierScalesThePositionAndIsRecorded() {
		when(lessons.evaluate(any())).thenReturn(new LessonEffect(0, null, null, 0.5, List.of()));
		when(trades.existsByRecommendationId(7L)).thenReturn(false);
		when(prices.latestPrice("AAPL")).thenReturn(Optional.of(bd(100)));
		when(benchmark.latest()).thenReturn(Optional.empty());
		when(trades.save(any())).thenAnswer(i -> i.getArgument(0));

		SimulatedTrade leg = investor.open(rec("AAPL", SignalDirection.BULLISH, 7L)).get(0);

		assertEquals(0, leg.getNotional().compareTo(new BigDecimal("50.00")), "half the usual $100");
		assertEquals(0, leg.getSizeMultiplier().compareTo(new BigDecimal("0.5")));
	}

	@Test
	void aLessonBlockRuleStopsTheTradeFromOpeningAtAll() {
		when(lessons.evaluate(any())).thenReturn(new LessonEffect(0, "this setup keeps losing", null, 1.0, List.of()));
		when(trades.existsByRecommendationId(7L)).thenReturn(false);
		when(prices.latestPrice("AAPL")).thenReturn(Optional.of(bd(100)));

		assertTrue(investor.open(rec("AAPL", SignalDirection.BULLISH, 7L)).isEmpty());
		verify(trades, never()).save(any());
	}

	// ---- early exits: the stop, and Agent 11 changing its mind ----

	private SimulatedTrade openTrade(SignalDirection dir, double entry, double stop, int horizon) {
		SimulatedTrade t = new SimulatedTrade(9L, "AAPL", dir, bd(100), BigDecimal.valueOf(entry), horizon, null);
		t.applyRisk(BigDecimal.valueOf(stop), 1.0);
		when(trades.findByStatus(SimulatedTrade.Status.OPEN)).thenReturn(List.of(t));
		when(benchmark.latest()).thenReturn(Optional.empty());
		when(gateway.generate(anyString())).thenReturn("reflection");
		return t;
	}

	@Test
	void aBrokenStopClosesALongEarlyAndSaysWhy() {
		SimulatedTrade t = openTrade(SignalDirection.BULLISH, 50, 45, 30); // 30-day horizon, nowhere near due
		when(prices.latestPrice("AAPL")).thenReturn(Optional.of(bd(44)));

		investor.closeDueTrades();

		assertEquals(SimulatedTrade.Status.CLOSED, t.getStatus());
		assertEquals("STOP", t.getExitReason());
		verify(graduation).recordOutcome(eq(false), eq(9L));
	}

	@Test
	void aShortIsStoppedOutWhenPriceRisesThroughItsStop() {
		SimulatedTrade t = openTrade(SignalDirection.BEARISH, 50, 55, 30);
		when(prices.latestPrice("AAPL")).thenReturn(Optional.of(bd(56)));

		investor.closeDueTrades();

		assertEquals("STOP", t.getExitReason());
	}

	@Test
	void aTradeThatIsNotDueAndNotStoppedStaysOpen() {
		SimulatedTrade t = openTrade(SignalDirection.BULLISH, 50, 45, 30);
		when(prices.latestPrice("AAPL")).thenReturn(Optional.of(bd(48)));

		investor.closeDueTrades();

		assertEquals(SimulatedTrade.Status.OPEN, t.getStatus());
		verify(graduation, never()).recordOutcome(anyBoolean(), any());
	}

	private static DeepAnalysis deepVerdict(DeepVerdict v, int conviction) {
		DeepAnalysis d = new DeepAnalysis("AAPL", "TEST");
		d.complete(v, null, conviction, "h", "t", "b", "b", "", "", "", "", Duration.ofDays(3));
		return d;
	}

	private static void pause() {
		try {
			Thread.sleep(15);
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
		}
	}

	@Test
	void aConfidentOppositeDeepVerdictAfterEntryClosesTheTradeAsAThesisFlip() {
		SimulatedTrade t = openTrade(SignalDirection.BULLISH, 50, 30, 30);
		pause();
		when(deepAnalyses.latestDone("AAPL")).thenReturn(Optional.of(deepVerdict(DeepVerdict.NOT_WORTH_BUYING, 70)));
		when(prices.latestPrice("AAPL")).thenReturn(Optional.of(bd(51)));

		investor.closeDueTrades();

		assertEquals(SimulatedTrade.Status.CLOSED, t.getStatus());
		assertEquals("THESIS_FLIP", t.getExitReason());
	}

	@Test
	void aWorthBuyingVerdictFlipsAShortToo() {
		SimulatedTrade t = openTrade(SignalDirection.BEARISH, 50, 70, 30);
		pause();
		when(deepAnalyses.latestDone("AAPL")).thenReturn(Optional.of(deepVerdict(DeepVerdict.WORTH_BUYING, 80)));
		when(prices.latestPrice("AAPL")).thenReturn(Optional.of(bd(50)));

		investor.closeDueTrades();

		assertEquals("THESIS_FLIP", t.getExitReason());
	}

	@Test
	void aWeakAgreeingOrPreEntryDeepVerdictDoesNotFlipAnything() {
		SimulatedTrade t = openTrade(SignalDirection.BULLISH, 50, 30, 30);
		when(prices.latestPrice("AAPL")).thenReturn(Optional.of(bd(51)));

		// Below the conviction bar.
		pause();
		when(deepAnalyses.latestDone("AAPL")).thenReturn(Optional.of(deepVerdict(DeepVerdict.NOT_WORTH_BUYING, 50)));
		investor.closeDueTrades();
		assertEquals(SimulatedTrade.Status.OPEN, t.getStatus(), "conviction below 60 is not enough to flip");

		// Agrees with the position.
		when(deepAnalyses.latestDone("AAPL")).thenReturn(Optional.of(deepVerdict(DeepVerdict.WORTH_BUYING, 90)));
		investor.closeDueTrades();
		assertEquals(SimulatedTrade.Status.OPEN, t.getStatus());

		// Finished BEFORE the trade opened: the trade was opened knowing it.
		DeepAnalysis before = deepVerdict(DeepVerdict.NOT_WORTH_BUYING, 90);
		pause();
		SimulatedTrade later = openTrade(SignalDirection.BULLISH, 50, 30, 30);
		when(deepAnalyses.latestDone("AAPL")).thenReturn(Optional.of(before));
		investor.closeDueTrades();
		assertEquals(SimulatedTrade.Status.OPEN, later.getStatus());
	}

	// ---- post-mortems are grounded in what Argus believed at entry ----

	@Test
	void thePostMortemPromptCitesTheOriginalThesisAndForbidsInventingCauses() {
		SimulatedTrade t = openTrade(SignalDirection.BULLISH, 50, 45, 30);
		when(prices.latestPrice("AAPL")).thenReturn(Optional.of(bd(44)));
		Recommendation rec = mock(Recommendation.class);
		when(rec.getThesis()).thenReturn("Buy AAPL — news and insiders agree.");
		when(rec.getReasons()).thenReturn("Company news: strong beat");
		when(rec.getCaveats()).thenReturn("Earnings are close");
		when(rec.getFeatures()).thenReturn("[\"dir=BULLISH\"]");
		when(rec.getLessons()).thenReturn("");
		when(recommendationRepo.findById(9L)).thenReturn(Optional.of(rec));

		investor.closeDueTrades();

		org.mockito.ArgumentCaptor<String> prompt = org.mockito.ArgumentCaptor.forClass(String.class);
		verify(gateway).generate(prompt.capture());
		assertTrue(prompt.getValue().contains("Use ONLY the facts below"));
		assertTrue(prompt.getValue().contains("Buy AAPL — news and insiders agree.") && prompt.getValue().contains("ended by STOP"));
		assertTrue(prompt.getValue().contains("ordinary market noise rather than inventing a cause"));
		assertEquals("reflection", t.getReview());
	}

	@Test
	void anAtRiskVerdictThatAgreesWithThePositionClosesItEvenWithoutAnOppositeCall() {
		SimulatedTrade t = openTrade(SignalDirection.BULLISH, 50, 30, 30);
		pause();
		DeepAnalysis d = deepVerdict(DeepVerdict.WORTH_BUYING, 90);
		d.flagAtRisk("A filing since the analysis reads negative.");
		when(deepAnalyses.latestDone("AAPL")).thenReturn(Optional.of(d));
		when(prices.latestPrice("AAPL")).thenReturn(Optional.of(bd(51)));

		investor.closeDueTrades();

		assertEquals(SimulatedTrade.Status.CLOSED, t.getStatus());
		assertEquals("THESIS_FLIP", t.getExitReason());
	}
}
