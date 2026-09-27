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
import com.argus.deepanalysis.DeepAnalysis;
import com.argus.deepanalysis.DeepAnalysisRepository;
import com.argus.deepanalysis.DeepVerdict;
import com.argus.fundamentals.Fundamentals;
import com.argus.fundamentals.FundamentalsService;
import com.argus.internet.WebMentionRepository;
import com.argus.regime.MarketRegime;
import com.argus.regime.MarketRegimeService;
import com.argus.regime.Sector;
import com.argus.regime.SectorClassifier;
import com.argus.sec.SecFilingRepository;
import com.argus.social.SocialPostRepository;
import com.argus.technical.ChartStudy;
import com.argus.technical.ChartStudyService;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
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
	private final ChartStudyService chartStudies = mock(ChartStudyService.class);
	private final FundamentalsService fundamentals = mock(FundamentalsService.class);
	private final DeepAnalysisRepository deepAnalyses = mock(DeepAnalysisRepository.class);
	private final SectorClassifier sectors = mock(SectorClassifier.class);
	private final MarketRegimeService regimes = mock(MarketRegimeService.class);
	private final com.argus.filings.FilingDigestService filings = mock(com.argus.filings.FilingDigestService.class);
	private final AgentSignalGatherer gatherer = new AgentSignalGatherer(news, social, sec, web, quietPeriod,
			tuning, chartStudies, fundamentals, deepAnalyses, sectors, regimes, filings);

	{
		// Tuning off by default in these tests → identity weight multipliers.
		when(tuning.weightMultiplier(anyString())).thenReturn(1.0);
		// Neutral sector + no market-regime data by default (macro is sector-aware and regime-muted).
		when(sectors.sectorOf(anyString())).thenReturn(Sector.BROAD_MARKET);
		when(regimes.current()).thenReturn(MarketRegime.unavailable());
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

	@Test
	void newsWeightScalesWithSentimentStrengthNotJustStoryCount() {
		// Same coverage and relevance; only the sentiment magnitude differs. A barely-positive 0.11
		// average must not carry the same weight as a decisive read.
		when(quietPeriod.statusFor("AAPL")).thenReturn(QuietPeriodStatus.clear());
		List<NewsArticle> weak = new ArrayList<>();
		List<NewsArticle> strong = new ArrayList<>();
		for (int i = 0; i < 6; i++) {
			weak.add(analyzed("weak story " + i, "Reuters", SentimentLabel.BULLISH, 0.12, 0.8));
			strong.add(analyzed("strong story " + i, "Reuters", SentimentLabel.BULLISH, 0.6, 0.8));
		}
		when(news.findAnalyzedForTicker(eq("AAPL"), any())).thenReturn(weak);
		double weakWeight = gatherer.gather("AAPL").get(0).weight();
		when(news.findAnalyzedForTicker(eq("AAPL"), any())).thenReturn(strong);
		double strongWeight = gatherer.gather("AAPL").get(0).weight();

		assertTrue(strongWeight > weakWeight * 2.5, "weak=" + weakWeight + " strong=" + strongWeight);
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
		assertTrue(signals.get(0).rationale().startsWith("Macro ("));
	}

	@Test
	void macroSignalIsIdenticalAcrossTickersInTheSameSector() {
		// A macro story isn't about any one ticker, so two tickers in the same sector see the same read
		// (the mock classifier puts both in BROAD_MARKET). Different sectors are covered below.
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

	@Test
	void macroReadDiffersBySector() {
		// An oil-supply shock is bad news for the market but good for energy names — the old code
		// stamped one identical number on every ticker and could not express that.
		when(sectors.sectorOf("XOM")).thenReturn(Sector.ENERGY);
		when(sectors.sectorOf("AAPL")).thenReturn(Sector.TECHNOLOGY);
		when(news.findAnalyzedForTicker(eq(MacroRelevanceTagger.MACRO_TAG), any())).thenReturn(List.of(
				analyzed("Oil prices surge as OPEC cuts output", "Reuters", SentimentLabel.BEARISH, -0.6, 0.9,
						new String[] {MacroRelevanceTagger.MACRO_TAG})));
		when(news.findAnalyzedForTicker(eq("XOM"), any())).thenReturn(List.of());
		when(news.findAnalyzedForTicker(eq("AAPL"), any())).thenReturn(List.of());
		when(quietPeriod.statusFor(anyString())).thenReturn(QuietPeriodStatus.clear());

		AgentSignal energy = gatherer.gather("XOM").get(0);
		AgentSignal tech = gatherer.gather("AAPL").get(0);

		assertEquals(SignalDirection.BULLISH, energy.direction(), "oil shock reads bullish for energy");
		assertEquals(SignalDirection.BEARISH, tech.direction(), "…and bearish for a non-energy name");
	}

	@Test
	void macroWeightIsMutedOnABroadSelloffDay() {
		when(news.findAnalyzedForTicker(eq(MacroRelevanceTagger.MACRO_TAG), any())).thenReturn(List.of(
				analyzed("Trump speech rattles markets", "Reuters", SentimentLabel.BEARISH, -0.7, 0.9,
						new String[] {MacroRelevanceTagger.MACRO_TAG})));
		when(news.findAnalyzedForTicker(eq("AAPL"), any())).thenReturn(List.of());
		when(quietPeriod.statusFor(anyString())).thenReturn(QuietPeriodStatus.clear());

		double calm = gatherer.gather("AAPL").get(0).weight();
		when(regimes.current()).thenReturn(
				new MarketRegime(Instant.now(), -2.0, -2.6, 30.0, 20.0, null, java.util.Map.of()));
		AgentSignal shock = gatherer.gather("AAPL").get(0);

		assertTrue(shock.weight() < calm * 0.5, "shock-day headlines fade — weight must be cut sharply");
		assertTrue(shock.rationale().contains("muted"));
	}

	@Test
	void euphoricCrowdIsTreatedAsCautionNotAVoteForTheCall() {
		when(news.findAnalyzedForTicker(eq("AAPL"), any())).thenReturn(List.of());
		when(quietPeriod.statusFor(anyString())).thenReturn(QuietPeriodStatus.clear());
		when(social.sentimentCountsForTicker(eq("AAPL"), any())).thenReturn(List.of(
				new Object[] {SentimentLabel.BULLISH, 45L}, new Object[] {SentimentLabel.BEARISH, 3L}));

		AgentSignal crowd = gatherer.gather("AAPL").get(0);

		assertEquals("agent-2-social", crowd.agent());
		assertEquals(SignalDirection.NEUTRAL, crowd.direction(), "a ~unanimous crowd is euphoria, not conviction");
		assertTrue(crowd.rationale().contains("contrarian"));
	}

	@Test
	void moderatelyBullishCrowdStillVotesBullishButWithLowCap() {
		when(news.findAnalyzedForTicker(eq("AAPL"), any())).thenReturn(List.of());
		when(quietPeriod.statusFor(anyString())).thenReturn(QuietPeriodStatus.clear());
		when(social.sentimentCountsForTicker(eq("AAPL"), any())).thenReturn(List.of(
				new Object[] {SentimentLabel.BULLISH, 24L}, new Object[] {SentimentLabel.BEARISH, 12L}));

		AgentSignal crowd = gatherer.gather("AAPL").get(0);

		assertEquals(SignalDirection.BULLISH, crowd.direction());
		assertTrue(crowd.weight() <= 0.3, "social influence is capped at 0.3");
	}

	@Test
	void signalsCarryAnEvidenceHorizonHint() {
		when(news.findAnalyzedForTicker(eq("AAPL"), any())).thenReturn(
				List.of(analyzed(SentimentLabel.BULLISH, 0.6, 0.8)));
		when(quietPeriod.statusFor("AAPL")).thenReturn(QuietPeriodStatus.clear());

		AgentSignal newsSignal = gatherer.gather("AAPL").get(0);

		assertEquals(10, newsSignal.horizonHintDays(), "a headline is a ~week-scale edge");
	}

	// ---- Agent 10: the chart study ----

	private static ChartStudy chart(double score, ChartStudy.Trend trend, Double sma200) {
		String bias = score >= 0.25 ? "BULLISH" : score <= -0.25 ? "BEARISH" : "NEUTRAL";
		return new ChartStudy(300, LocalDate.now(), 100, 1.0, 2.0, 3.0, 100.0, 98.0, sma200, trend, 55.0, 0.5, 0.5, 2.0, 1.0,
				1.0, 50.0, -3.0, 95.0, 105.0, List.of(), 1.0, 2.0, score, bias, List.of(
						"Trend: " + trend + " — close 100.00 vs SMA20 100.00 / SMA50 98.00.",
						"Candlestick (2026-09-23): Hammer — bullish, after a 3.0% decline.",
						"Relative strength vs S&P 500: +2.0 pts over 20 sessions, +5.0 pts over 60 sessions."));
	}

	@Test
	void aDecisiveBullishChartBecomesABullishTechnicalSignalWithTheStudysEvidence() {
		when(quietPeriod.statusFor("AAPL")).thenReturn(QuietPeriodStatus.clear());
		when(chartStudies.studyFor("AAPL")).thenReturn(Optional.of(chart(0.6, ChartStudy.Trend.UPTREND, 90.0)));

		AgentSignal t = gatherer.gather("AAPL").stream().filter(x -> x.agent().equals("agent-10-technical")).findFirst().orElseThrow();

		assertEquals(SignalDirection.BULLISH, t.direction());
		assertEquals(0.7 * (0.6 / 0.7), t.weight(), 1e-9);
		assertTrue(t.rationale().contains("Trend:") && t.rationale().contains("Candlestick") && t.rationale().contains("Relative strength"));
		assertEquals(30, t.horizonHintDays(), "a trend-led read (with a 200-day average) is a slower edge");
	}

	@Test
	void aChartWithoutATrendCarriesAShortHorizonHint() {
		when(quietPeriod.statusFor("AAPL")).thenReturn(QuietPeriodStatus.clear());
		when(chartStudies.studyFor("AAPL")).thenReturn(Optional.of(chart(0.4, ChartStudy.Trend.SIDEWAYS, null)));

		AgentSignal t = gatherer.gather("AAPL").stream().filter(x -> x.agent().equals("agent-10-technical")).findFirst().orElseThrow();

		assertEquals(10, t.horizonHintDays(), "pattern / mean-reversion reads resolve within about two weeks");
	}

	@Test
	void aBearishChartIsABearishSignal() {
		when(quietPeriod.statusFor("AAPL")).thenReturn(QuietPeriodStatus.clear());
		when(chartStudies.studyFor("AAPL")).thenReturn(Optional.of(chart(-0.5, ChartStudy.Trend.DOWNTREND, 110.0)));

		AgentSignal t = gatherer.gather("AAPL").stream().filter(x -> x.agent().equals("agent-10-technical")).findFirst().orElseThrow();

		assertEquals(SignalDirection.BEARISH, t.direction());
	}

	@Test
	void anIndecisiveChartOrNoChartEmitsNothing() {
		when(quietPeriod.statusFor("AAPL")).thenReturn(QuietPeriodStatus.clear());
		when(chartStudies.studyFor("AAPL")).thenReturn(Optional.of(chart(0.1, ChartStudy.Trend.SIDEWAYS, null)));
		assertTrue(gatherer.gather("AAPL").stream().noneMatch(x -> x.agent().equals("agent-10-technical")));

		when(chartStudies.studyFor("AAPL")).thenReturn(Optional.empty()); // not enough history yet
		assertTrue(gatherer.gather("AAPL").stream().noneMatch(x -> x.agent().equals("agent-10-technical")));
	}

	// ---- Agent 12: fundamentals ----

	private static Fundamentals fundamentalsOf(boolean applicable, double score) {
		return new Fundamentals("AAPL", applicable, "Apple", "Tech", 3000.0, java.util.Map.of(), List.of(), List.of(), null, null, score,
				score >= 0.2 ? "BULLISH" : score <= -0.2 ? "BEARISH" : "NEUTRAL",
				List.of("Growth: revenue +12.0% year-over-year.", "Profitability: net margin 25.0%."), Instant.now());
	}

	@Test
	void strongFundamentalsBecomeALongHorizonBullishSignal() {
		when(quietPeriod.statusFor("AAPL")).thenReturn(QuietPeriodStatus.clear());
		when(fundamentals.latestFresh(eq("AAPL"), any())).thenReturn(Optional.of(fundamentalsOf(true, 0.5)));

		AgentSignal f = gatherer.gather("AAPL").stream().filter(x -> x.agent().equals("agent-12-fundamental")).findFirst().orElseThrow();

		assertEquals(SignalDirection.BULLISH, f.direction());
		assertEquals(90, f.horizonHintDays(), "fundamentals move over quarters — this argues for a long hold");
		assertEquals(0.6 * (0.5 / 0.6), f.weight(), 1e-9);
	}

	@Test
	void etfsStaleSnapshotsAndWeakScoresEmitNoFundamentalSignal() {
		when(quietPeriod.statusFor("AAPL")).thenReturn(QuietPeriodStatus.clear());
		when(fundamentals.latestFresh(eq("AAPL"), any())).thenReturn(Optional.of(fundamentalsOf(false, 0.0)));
		assertTrue(gatherer.gather("AAPL").stream().noneMatch(x -> x.agent().equals("agent-12-fundamental")), "ETF / no company data");

		when(fundamentals.latestFresh(eq("AAPL"), any())).thenReturn(Optional.of(fundamentalsOf(true, 0.1)));
		assertTrue(gatherer.gather("AAPL").stream().noneMatch(x -> x.agent().equals("agent-12-fundamental")), "inside the deadzone");

		when(fundamentals.latestFresh(eq("AAPL"), any())).thenReturn(Optional.empty());
		assertTrue(gatherer.gather("AAPL").stream().noneMatch(x -> x.agent().equals("agent-12-fundamental")), "stale / missing");
	}

	// ---- Agent 11: the deep analyst's stored verdict ----

	private static DeepAnalysis deep(DeepVerdict verdict, Integer hold, int conviction, java.time.Duration ttl) {
		DeepAnalysis d = new DeepAnalysis("AAPL", "TEST");
		d.complete(verdict, hold, conviction, "Headline", "Thesis", "Bull", "Bear", "", "", "", "", ttl);
		return d;
	}

	@Test
	void aFreshWorthBuyingVerdictIsABullishSignalCarryingItsHoldPeriod() {
		when(quietPeriod.statusFor("AAPL")).thenReturn(QuietPeriodStatus.clear());
		when(deepAnalyses.findFirstByTickerAndStatusOrderByFinishedAtDesc("AAPL", DeepAnalysis.Status.DONE))
				.thenReturn(Optional.of(deep(DeepVerdict.WORTH_BUYING, 30, 80, java.time.Duration.ofDays(3))));

		AgentSignal d = gatherer.gather("AAPL").stream().filter(x -> x.agent().equals("agent-11-deep")).findFirst().orElseThrow();

		assertEquals(SignalDirection.BULLISH, d.direction());
		assertEquals(1.2 * 0.8, d.weight(), 1e-9);
		assertEquals(30, d.horizonHintDays());
		assertTrue(d.rationale().contains("hold ~30 days"));
	}

	@Test
	void aNotWorthBuyingVerdictIsABearishSignal() {
		when(quietPeriod.statusFor("AAPL")).thenReturn(QuietPeriodStatus.clear());
		when(deepAnalyses.findFirstByTickerAndStatusOrderByFinishedAtDesc("AAPL", DeepAnalysis.Status.DONE))
				.thenReturn(Optional.of(deep(DeepVerdict.NOT_WORTH_BUYING, null, 70, java.time.Duration.ofDays(3))));

		AgentSignal d = gatherer.gather("AAPL").stream().filter(x -> x.agent().equals("agent-11-deep")).findFirst().orElseThrow();

		assertEquals(SignalDirection.BEARISH, d.direction());
	}

	@Test
	void waitExpiredOrMissingDeepVerdictsEmitNothing() {
		when(quietPeriod.statusFor("AAPL")).thenReturn(QuietPeriodStatus.clear());
		when(deepAnalyses.findFirstByTickerAndStatusOrderByFinishedAtDesc("AAPL", DeepAnalysis.Status.DONE))
				.thenReturn(Optional.of(deep(DeepVerdict.WAIT, null, 50, java.time.Duration.ofDays(3))));
		assertTrue(gatherer.gather("AAPL").stream().noneMatch(x -> x.agent().equals("agent-11-deep")), "WAIT is not a signal");

		when(deepAnalyses.findFirstByTickerAndStatusOrderByFinishedAtDesc("AAPL", DeepAnalysis.Status.DONE))
				.thenReturn(Optional.of(deep(DeepVerdict.WORTH_BUYING, 30, 80, java.time.Duration.ofDays(-1))));
		assertTrue(gatherer.gather("AAPL").stream().noneMatch(x -> x.agent().equals("agent-11-deep")), "an expired verdict is stale");

		when(deepAnalyses.findFirstByTickerAndStatusOrderByFinishedAtDesc("AAPL", DeepAnalysis.Status.DONE)).thenReturn(Optional.empty());
		assertTrue(gatherer.gather("AAPL").stream().noneMatch(x -> x.agent().equals("agent-11-deep")));
	}

	@Test
	void gatherBaseExcludesAgent11SoTheDeepAnalystReasonsOverTheOtherAgentsNotItself() {
		when(quietPeriod.statusFor("AAPL")).thenReturn(QuietPeriodStatus.clear());
		when(deepAnalyses.findFirstByTickerAndStatusOrderByFinishedAtDesc("AAPL", DeepAnalysis.Status.DONE))
				.thenReturn(Optional.of(deep(DeepVerdict.WORTH_BUYING, 30, 80, java.time.Duration.ofDays(3))));

		assertTrue(gatherer.gatherBase("AAPL").stream().noneMatch(x -> x.agent().equals("agent-11-deep")));
		assertTrue(gatherer.gather("AAPL").stream().anyMatch(x -> x.agent().equals("agent-11-deep")));
	}

	// ---- Agent 14: filings ----

	private static com.argus.filings.FilingView filingView(double score, String guidance, long ageDays) {
		return new com.argus.filings.FilingView("AAPL", score, guidance, "CONFIDENT", java.time.LocalDate.now().minusDays(ageDays), "Raised full-year guidance",
				ageDays, List.of());
	}

	@Test
	void aFreshPositiveFilingIsABullishSignalWithAQuarterlyHorizon() {
		when(quietPeriod.statusFor("AAPL")).thenReturn(QuietPeriodStatus.clear());
		when(filings.view("AAPL")).thenReturn(Optional.of(filingView(0.6, "RAISED", 3)));

		AgentSignal f = gatherer.gather("AAPL").stream().filter(x -> x.agent().equals("agent-14-filings")).findFirst().orElseThrow();

		assertEquals(SignalDirection.BULLISH, f.direction());
		assertEquals(0.7, f.weight(), 1e-9);
		assertEquals(30, f.horizonHintDays());
		assertTrue(f.rationale().contains("guidance raised"), f.rationale());
		assertEquals(com.argus.learning.SignalGroup.FILINGS, com.argus.learning.SignalGroup.of(f.agent()));
	}

	@Test
	void aNegativeFilingIsBearishAndStaleOrWeakOnesEmitNothing() {
		when(quietPeriod.statusFor("AAPL")).thenReturn(QuietPeriodStatus.clear());
		when(filings.view("AAPL")).thenReturn(Optional.of(filingView(-0.5, "LOWERED", 5)));
		assertEquals(SignalDirection.BEARISH, gatherer.gather("AAPL").stream().filter(x -> x.agent().equals("agent-14-filings")).findFirst().orElseThrow().direction());

		when(filings.view("AAPL")).thenReturn(Optional.of(filingView(0.6, "RAISED", 90)));
		assertTrue(gatherer.gather("AAPL").stream().noneMatch(x -> x.agent().equals("agent-14-filings")), "older than 45 days is stale");

		when(filings.view("AAPL")).thenReturn(Optional.of(filingView(0.1, "MAINTAINED", 2)));
		assertTrue(gatherer.gather("AAPL").stream().noneMatch(x -> x.agent().equals("agent-14-filings")), "inside the deadzone");

		when(filings.view("AAPL")).thenThrow(new RuntimeException("db down"));
		assertTrue(gatherer.gather("AAPL").stream().noneMatch(x -> x.agent().equals("agent-14-filings")), "a failing read must not break the gather");
	}

	@Test
	void standingCarriesGuidanceAndValuationForTheLearnersFeatureTokens() {
		when(filings.view("AAPL")).thenReturn(Optional.of(filingView(0.6, "RAISED", 3)));
		Fundamentals f = fundamentalsOf(true, 0.5);
		Fundamentals withVal = new Fundamentals(f.ticker(), f.applicable(), f.name(), f.industry(), f.marketCapMillions(), f.ratios(), f.quarters(), f.earnings(),
				f.analysts(), f.peers(), f.score(), f.bias(), f.notes(), f.fetchedAt(),
				new Fundamentals.ValuationView(9.0, 4.0, 9.0, 5.0, "RICH", 100.0, 5.0, "priced for growth"));
		when(fundamentals.latestFresh(eq("AAPL"), any())).thenReturn(Optional.of(withVal));

		var s = gatherer.standing("AAPL");

		assertEquals("RAISED", s.guidance());
		assertEquals("RICH", s.valuation());
	}

	@Test
	void aDeepVerdictTheThesisTrackerFlaggedAtRiskIsNoLongerASignal() {
		when(quietPeriod.statusFor("AAPL")).thenReturn(QuietPeriodStatus.clear());
		DeepAnalysis d = deep(DeepVerdict.WORTH_BUYING, 30, 80, java.time.Duration.ofDays(3));
		d.flagAtRisk("Price fell through the invalidation level.");
		when(deepAnalyses.findFirstByTickerAndStatusOrderByFinishedAtDesc("AAPL", DeepAnalysis.Status.DONE)).thenReturn(Optional.of(d));

		assertTrue(gatherer.gather("AAPL").stream().noneMatch(x -> x.agent().equals("agent-11-deep")));
	}
}
