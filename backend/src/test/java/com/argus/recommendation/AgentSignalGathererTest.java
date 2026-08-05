package com.argus.recommendation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.argus.calendar.EarningsQuietPeriodService;
import com.argus.calendar.QuietPeriodStatus;
import com.argus.intelligence.MacroRelevanceTagger;
import com.argus.intelligence.NewsArticle;
import com.argus.intelligence.NewsArticleRepository;
import com.argus.intelligence.SentimentAnalysis;
import com.argus.intelligence.SentimentLabel;
import com.argus.internet.WebMentionRepository;
import com.argus.sec.SecFilingRepository;
import com.argus.social.SocialPostRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Agent 5's signal assembly from news + macro + calendar (Story 6.4). */
class AgentSignalGathererTest {

	private final NewsArticleRepository news = mock(NewsArticleRepository.class);
	private final SocialPostRepository social = mock(SocialPostRepository.class);
	private final SecFilingRepository sec = mock(SecFilingRepository.class);
	private final WebMentionRepository web = mock(WebMentionRepository.class);
	private final EarningsQuietPeriodService quietPeriod = mock(EarningsQuietPeriodService.class);
	private final AdaptiveTuningService tuning = mock(AdaptiveTuningService.class);
	private final AgentSignalGatherer gatherer =
			new AgentSignalGatherer(news, social, sec, web, quietPeriod, tuning);

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
}
