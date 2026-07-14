package com.argus.recommendation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.argus.calendar.EarningsQuietPeriodService;
import com.argus.calendar.QuietPeriodStatus;
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

/** Agent 5's signal assembly from news + calendar (Story 6.4). */
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
	}

	private static NewsArticle analyzed(SentimentLabel label, double score, double relevance) {
		return analyzed("headline " + Math.random(), "Reuters", label, score, relevance);
	}

	private static NewsArticle analyzed(String headline, String source, SentimentLabel label,
			double score, double relevance) {
		NewsArticle a = new NewsArticle(source, "id" + Math.random(), "u", headline, "s",
				Instant.now(), new String[] {"AAPL"});
		a.applySentiment(new SentimentAnalysis(label, score, relevance), Instant.now());
		return a;
	}

	@Test
	void bullishNewsBecomesABullishSignal() {
		when(news.findAnalyzedForTicker(anyString(), any())).thenReturn(List.of(
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
		when(news.findAnalyzedForTicker(anyString(), any())).thenReturn(List.of(
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
		when(news.findAnalyzedForTicker(anyString(), any())).thenReturn(List.of());
		when(quietPeriod.statusFor("AAPL"))
				.thenReturn(new QuietPeriodStatus(QuietPeriodStatus.Status.NOTE, LocalDate.now(), 4));

		List<AgentSignal> signals = gatherer.gather("AAPL");

		assertEquals(1, signals.size());
		assertEquals("agent-7-calendar", signals.get(0).agent());
		assertEquals(SignalDirection.BEARISH, signals.get(0).direction());
	}

	@Test
	void noNewsAndClearCalendarYieldsNoSignals() {
		when(news.findAnalyzedForTicker(anyString(), any())).thenReturn(List.of());
		when(quietPeriod.statusFor("AAPL")).thenReturn(QuietPeriodStatus.clear());
		assertTrue(gatherer.gather("AAPL").isEmpty());
	}
}
