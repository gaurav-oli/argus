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
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Agent 5's signal assembly from news + calendar (Story 6.4). */
class AgentSignalGathererTest {

	private final NewsArticleRepository news = mock(NewsArticleRepository.class);
	private final EarningsQuietPeriodService quietPeriod = mock(EarningsQuietPeriodService.class);
	private final AgentSignalGatherer gatherer = new AgentSignalGatherer(news, quietPeriod);

	private static NewsArticle analyzed(SentimentLabel label, double score, double relevance) {
		NewsArticle a = new NewsArticle("Reuters", "id" + Math.random(), "u", "h", "s",
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
