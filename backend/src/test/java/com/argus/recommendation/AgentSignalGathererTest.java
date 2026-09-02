package com.argus.recommendation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.argus.calendar.EarningsQuietPeriodService;
import com.argus.calendar.QuietPeriodStatus;
import com.argus.intelligence.MacroRelevanceTagger;
import com.argus.intelligence.NewsArticle;
import com.argus.intelligence.NewsArticleRepository;
import com.argus.intelligence.SentimentAnalysis;
import com.argus.intelligence.SentimentLabel;
import com.argus.internet.WebMentionRepository;
import com.argus.notification.Notification;
import com.argus.notification.NotificationService;
import com.argus.sec.SecFilingRepository;
import com.argus.social.SocialPostRepository;
import com.argus.technical.CauseClassificationService;
import com.argus.technical.PriceCandle;
import com.argus.technical.PriceCandleRepository;
import com.argus.technical.TechnicalAnalysisProperties;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Agent 5's signal assembly from news + macro + calendar + technical/cause (Story 6.4). */
class AgentSignalGathererTest {

	private final NewsArticleRepository news = mock(NewsArticleRepository.class);
	private final SocialPostRepository social = mock(SocialPostRepository.class);
	private final SecFilingRepository sec = mock(SecFilingRepository.class);
	private final WebMentionRepository web = mock(WebMentionRepository.class);
	private final EarningsQuietPeriodService quietPeriod = mock(EarningsQuietPeriodService.class);
	private final AdaptiveTuningService tuning = mock(AdaptiveTuningService.class);
	private final PriceCandleRepository candles = mock(PriceCandleRepository.class);
	private final CauseClassificationService causeClassification = mock(CauseClassificationService.class);
	private final TechnicalAnalysisProperties technicalProps = new TechnicalAnalysisProperties(200, -8.0, 0.6);
	private final NotificationService notifications = mock(NotificationService.class);
	private final AgentSignalGatherer gatherer = new AgentSignalGatherer(news, social, sec, web, quietPeriod,
			tuning, candles, causeClassification, technicalProps, notifications);

	{
		// Tuning off by default in these tests → identity weight multipliers.
		when(tuning.weightMultiplier(anyString())).thenReturn(1.0);
		// No macro coverage by default — tests that only care about ticker-specific news don't have
		// to think about the macro query too. Tests exercising macro override this explicitly.
		when(news.findAnalyzedForTicker(eq(MacroRelevanceTagger.MACRO_TAG), any())).thenReturn(List.of());
	}

	private static NewsArticle analyzed(SentimentLabel label, double score, double relevance) {
		return analyzed("headline " + Math.random(), "Reuters", label, score, relevance);
	}

	private static NewsArticle analyzed(String headline, String source, SentimentLabel label,
			double score, double relevance) {
		return analyzed(headline, source, label, score, relevance, new String[] {"AAPL"});
	}

	private static NewsArticle analyzed(String headline, String source, SentimentLabel label,
			double score, double relevance, String[] tickers) {
		NewsArticle a = new NewsArticle(source, "id" + Math.random(), "u", headline, "s",
				Instant.now(), tickers);
		a.applySentiment(new SentimentAnalysis(label, score, relevance, false), Instant.now());
		return a;
	}

	@Test
	void bullishNewsBecomesABullishSignal() {
		when(news.findAnalyzedForTicker(eq("AAPL"), any())).thenReturn(List.of(
				analyzed(SentimentLabel.BULLISH, 0.8, 0.9), analyzed(SentimentLabel.BULLISH, 0.6, 0.8)));
		when(quietPeriod.statusFor("AAPL")).thenReturn(QuietPeriodStatus.clear());

		List<AgentSignal> signals = gatherer.gather("AAPL");

		assertEquals(1, signals.size());
		assertEquals("agent-1-news", signals.get(0).agent());
		assertEquals(SignalDirection.BULLISH, signals.get(0).direction());
		assertTrue(signals.get(0).weight() > 0);
	}

	// ---- headline dedup clustering (Fable 5 follow-up) ----

	@Test
	void sameStoryAcrossSourcesCollapsesToOneCluster() {
		// One story via three sources with case/punctuation variants; the highest-relevance wins.
		List<NewsArticle> clustered = AgentSignalGatherer.clusterByHeadline(List.of(
				analyzed("NVIDIA beats Q2 estimates", "Finnhub", SentimentLabel.BULLISH, 0.8, 0.7),
				analyzed("Nvidia Beats Q2 Estimates!", "GDELT", SentimentLabel.BULLISH, 0.7, 0.9),
				analyzed("nvidia beats q2 estimates", "RSS", SentimentLabel.BULLISH, 0.6, 0.5)));

		assertEquals(1, clustered.size());
		assertEquals("GDELT", clustered.get(0).getSource()); // relevance 0.9 representative
	}

	@Test
	void distinctStoriesStayDistinct() {
		List<NewsArticle> clustered = AgentSignalGatherer.clusterByHeadline(List.of(
				analyzed("NVIDIA beats Q2 estimates", "Finnhub", SentimentLabel.BULLISH, 0.8, 0.7),
				analyzed("Tesla recalls 50,000 vehicles", "RSS", SentimentLabel.BEARISH, -0.6, 0.8)));

		assertEquals(2, clustered.size());
	}

	@Test
	void newsSignalScoresDistinctStoriesNotRawArticles() {
		// 4 raw articles but only 2 distinct stories → coverage counts 2 (rationale says so), and the
		// duplicated story's sentiment isn't double-counted into the average.
		when(news.findAnalyzedForTicker(eq("AAPL"), any())).thenReturn(List.of(
				analyzed("NVIDIA beats Q2 estimates", "Finnhub", SentimentLabel.BULLISH, 0.8, 0.8),
				analyzed("NVIDIA Beats Q2 Estimates", "GDELT", SentimentLabel.BULLISH, 0.8, 0.8),
				analyzed("NVIDIA beats q2 estimates!", "RSS", SentimentLabel.BULLISH, 0.8, 0.8),
				analyzed("Antitrust probe widens", "Reuters", SentimentLabel.BEARISH, -0.4, 0.8)));
		when(quietPeriod.statusFor("AAPL")).thenReturn(QuietPeriodStatus.clear());

		List<AgentSignal> signals = gatherer.gather("AAPL");

		AgentSignal newsSignal = signals.stream().filter(s -> s.agent().equals("agent-1-news"))
				.findFirst().orElseThrow();
		assertTrue(newsSignal.rationale().contains("2 distinct stories (4 articles)"));
		// avg over representatives = (0.8 − 0.4) / 2 = 0.2 → BULLISH (raw-article avg would be 0.5).
		assertEquals(SignalDirection.BULLISH, newsSignal.direction());
	}

	@Test
	void earningsNotePeriodAddsABearishCalendarSignal() {
		when(news.findAnalyzedForTicker(eq("AAPL"), any())).thenReturn(List.of());
		when(quietPeriod.statusFor("AAPL"))
				.thenReturn(new QuietPeriodStatus(QuietPeriodStatus.Status.NOTE, LocalDate.now(), 4));

		List<AgentSignal> signals = gatherer.gather("AAPL");

		assertEquals(1, signals.size());
		assertEquals("agent-7-calendar", signals.get(0).agent());
		assertEquals(SignalDirection.BEARISH, signals.get(0).direction());
	}

	@Test
	void noNewsAndClearCalendarYieldsNoSignals() {
		when(news.findAnalyzedForTicker(eq("AAPL"), any())).thenReturn(List.of());
		when(quietPeriod.statusFor("AAPL")).thenReturn(QuietPeriodStatus.clear());
		assertTrue(gatherer.gather("AAPL").isEmpty());
	}

	// ---- macro/political news (Agent 8) ----

	@Test
	void macroTaggedNewsBecomesAMacroSignal() {
		when(news.findAnalyzedForTicker(eq("AAPL"), any())).thenReturn(List.of());
		when(news.findAnalyzedForTicker(eq(MacroRelevanceTagger.MACRO_TAG), any())).thenReturn(List.of(
				analyzed("Trump announces new tariffs on Chinese imports", "Reuters",
						SentimentLabel.BEARISH, -0.7, 0.8, new String[] {MacroRelevanceTagger.MACRO_TAG})));
		when(quietPeriod.statusFor("AAPL")).thenReturn(QuietPeriodStatus.clear());

		List<AgentSignal> signals = gatherer.gather("AAPL");

		assertEquals(1, signals.size());
		assertEquals("agent-8-macro", signals.get(0).agent());
		assertEquals(SignalDirection.BEARISH, signals.get(0).direction());
		assertTrue(signals.get(0).rationale().contains("Macro/political"));
	}

	@Test
	void macroSignalIsIdenticalAcrossDifferentTickersInOneCycle() {
		// The whole point: a macro story isn't about any one ticker, so every ticker's gather() call
		// in the same cycle sees the same macro read.
		when(news.findAnalyzedForTicker(eq(MacroRelevanceTagger.MACRO_TAG), any())).thenReturn(List.of(
				analyzed("Fed signals rate cut", "Reuters", SentimentLabel.BULLISH, 0.6, 0.9,
						new String[] {MacroRelevanceTagger.MACRO_TAG})));
		when(news.findAnalyzedForTicker(eq("AAPL"), any())).thenReturn(List.of());
		when(news.findAnalyzedForTicker(eq("MSFT"), any())).thenReturn(List.of());
		when(quietPeriod.statusFor(anyString())).thenReturn(QuietPeriodStatus.clear());

		AgentSignal aaplMacro = gatherer.gather("AAPL").get(0);
		AgentSignal msftMacro = gatherer.gather("MSFT").get(0);

		assertEquals(aaplMacro.direction(), msftMacro.direction());
		assertEquals(aaplMacro.weight(), msftMacro.weight());
	}

	@Test
	void newsAndMacroSignalsCoexist() {
		when(news.findAnalyzedForTicker(eq("AAPL"), any())).thenReturn(
				List.of(analyzed(SentimentLabel.BULLISH, 0.6, 0.8)));
		when(news.findAnalyzedForTicker(eq(MacroRelevanceTagger.MACRO_TAG), any())).thenReturn(List.of(
				analyzed("White House announces executive order", "AP", SentimentLabel.BEARISH, -0.5, 0.7,
						new String[] {MacroRelevanceTagger.MACRO_TAG})));
		when(quietPeriod.statusFor("AAPL")).thenReturn(QuietPeriodStatus.clear());

		List<AgentSignal> signals = gatherer.gather("AAPL");

		assertEquals(2, signals.size());
		assertTrue(signals.stream().anyMatch(s -> s.agent().equals("agent-1-news")));
		assertTrue(signals.stream().anyMatch(s -> s.agent().equals("agent-8-macro")));
	}

	// ---- technical analysis + cause classification (Agents 10/11) ----

	/** Builds candles oldest→newest from {@code closes}, then returns them newest-first — matching
	 * what {@link PriceCandleRepository#findTop200ByTickerOrderByCandleDateDesc} actually returns
	 * (the gatherer reverses it internally). */
	private static List<PriceCandle> descendingCandles(double... closes) {
		List<PriceCandle> ascending = new ArrayList<>();
		LocalDate start = LocalDate.of(2026, 1, 1);
		for (int i = 0; i < closes.length; i++) {
			BigDecimal price = BigDecimal.valueOf(closes[i]);
			ascending.add(new PriceCandle("AAPL", start.plusDays(i), price, price, price, price, 1000L));
		}
		Collections.reverse(ascending);
		return ascending;
	}

	/** 20 rising candles (100..119, setting a ~119 high) then 15 sharply declining ones down to
	 * ~95 — a real ~20% drawdown crossing the -8% default trigger, with enough history for both
	 * RSI(14) and the 60-day-lookback drawdown check (the empty-check only needs half the window). */
	private static double[] risingThenSharpDrop() {
		double[] values = new double[35];
		for (int i = 0; i < 20; i++) {
			values[i] = 100 + i;
		}
		for (int i = 0; i < 15; i++) {
			values[20 + i] = 119 - (i + 1) * 1.6;
		}
		return values;
	}

	@Test
	void technicalSignalFromOversoldRsiIsBullish() {
		when(quietPeriod.statusFor("AAPL")).thenReturn(QuietPeriodStatus.clear());
		double[] declining = new double[15];
		for (int i = 0; i < 15; i++) {
			declining[i] = 24 - i; // strictly decreasing, 14 losses -> RSI = 0 (maximally oversold)
		}
		when(candles.findTop200ByTickerOrderByCandleDateDesc("AAPL")).thenReturn(descendingCandles(declining));

		List<AgentSignal> signals = gatherer.gather("AAPL");

		AgentSignal technical = signals.stream().filter(s -> s.agent().equals("agent-10-technical"))
				.findFirst().orElseThrow();
		assertEquals(SignalDirection.BULLISH, technical.direction());
		assertTrue(technical.weight() > 0);
	}

	@Test
	void noTechnicalOrCauseSignalWithoutCandleHistory() {
		when(quietPeriod.statusFor("AAPL")).thenReturn(QuietPeriodStatus.clear());
		// candles left unstubbed -> empty list, matching "not enough history yet" for a new ticker.

		List<AgentSignal> signals = gatherer.gather("AAPL");

		assertTrue(signals.stream().noneMatch(
				s -> s.agent().equals("agent-10-technical") || s.agent().equals("agent-11-cause")));
	}

	@Test
	void causeSignalFiresWhenClassificationIsConfidentMacroExternalAndTemporary() {
		when(quietPeriod.statusFor("AAPL")).thenReturn(QuietPeriodStatus.clear());
		when(candles.findTop200ByTickerOrderByCandleDateDesc("AAPL"))
				.thenReturn(descendingCandles(risingThenSharpDrop()));
		when(causeClassification.classify(eq("AAPL"), anyDouble())).thenReturn(Optional.of(
				new CauseClassificationService.Classification(CauseClassificationService.Cause.MACRO_EXTERNAL,
						true, 0.8, "Broad market selloff, unrelated to this company")));

		List<AgentSignal> signals = gatherer.gather("AAPL");

		AgentSignal cause = signals.stream().filter(s -> s.agent().equals("agent-11-cause"))
				.findFirst().orElseThrow();
		assertEquals(SignalDirection.BULLISH, cause.direction());
		assertEquals(1.5 * 0.8, cause.weight(), 0.001,
				"weight must be computed here from confidence, never the LLM's own number");
		verify(notifications).notify(any(Notification.class));
	}

	@Test
	void noAlertWhenCauseSignalDoesNotFire() {
		when(quietPeriod.statusFor("AAPL")).thenReturn(QuietPeriodStatus.clear());
		when(candles.findTop200ByTickerOrderByCandleDateDesc("AAPL"))
				.thenReturn(descendingCandles(risingThenSharpDrop()));
		when(causeClassification.classify(eq("AAPL"), anyDouble())).thenReturn(Optional.of(
				new CauseClassificationService.Classification(CauseClassificationService.Cause.COMPANY_SPECIFIC,
						true, 0.9, "Real earnings miss")));

		gatherer.gather("AAPL");

		verify(notifications, never()).notify(any());
	}

	@Test
	void causeClassificationNeverCalledOnAShallowDip() {
		when(quietPeriod.statusFor("AAPL")).thenReturn(QuietPeriodStatus.clear());
		double[] mild = new double[35];
		for (int i = 0; i < 35; i++) {
			mild[i] = 100 + (i % 2 == 0 ? 1 : 0); // oscillates near the high — shallower than -8%
		}
		when(candles.findTop200ByTickerOrderByCandleDateDesc("AAPL")).thenReturn(descendingCandles(mild));

		gatherer.gather("AAPL");

		verify(causeClassification, never()).classify(anyString(), anyDouble());
	}

	@Test
	void causeSignalAbsentWhenClassifiedCompanySpecific() {
		when(quietPeriod.statusFor("AAPL")).thenReturn(QuietPeriodStatus.clear());
		when(candles.findTop200ByTickerOrderByCandleDateDesc("AAPL"))
				.thenReturn(descendingCandles(risingThenSharpDrop()));
		when(causeClassification.classify(eq("AAPL"), anyDouble())).thenReturn(Optional.of(
				new CauseClassificationService.Classification(CauseClassificationService.Cause.COMPANY_SPECIFIC,
						true, 0.9, "Real earnings miss")));

		List<AgentSignal> signals = gatherer.gather("AAPL");

		assertTrue(signals.stream().noneMatch(s -> s.agent().equals("agent-11-cause")));
	}

	@Test
	void causeSignalAbsentWhenConfidenceBelowFloor() {
		when(quietPeriod.statusFor("AAPL")).thenReturn(QuietPeriodStatus.clear());
		when(candles.findTop200ByTickerOrderByCandleDateDesc("AAPL"))
				.thenReturn(descendingCandles(risingThenSharpDrop()));
		when(causeClassification.classify(eq("AAPL"), anyDouble())).thenReturn(Optional.of(
				new CauseClassificationService.Classification(CauseClassificationService.Cause.MACRO_EXTERNAL,
						true, 0.4, "Somewhat macro-related")));

		List<AgentSignal> signals = gatherer.gather("AAPL");

		assertTrue(signals.stream().noneMatch(s -> s.agent().equals("agent-11-cause")));
	}
}
