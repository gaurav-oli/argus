package com.argus.intelligence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.argus.agent.EventEnvelope;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** Sentiment agent: loads the article, scores it, persists; idempotent and null-safe (Story 4.2). */
class NewsSentimentAgentTest {

	private final NewsArticleRepository articles = mock(NewsArticleRepository.class);
	private final SentimentAnalyzer analyzer = mock(SentimentAnalyzer.class);
	private final NewsSentimentAgent agent = new NewsSentimentAgent(articles, analyzer);

	private static EventEnvelope event(Object articleId) {
		Map<String, Object> payload = articleId == null ? Map.of() : Map.of("articleId", articleId);
		return new EventEnvelope("e1", "news.article.ingested", Instant.now(), 1, payload);
	}

	private static NewsArticle article() {
		return new NewsArticle("finnhub", "x1", "http://x", "AAPL surges", "summary",
				Instant.now(), new String[] {"AAPL"});
	}

	@Test
	void scoresAndPersistsTheArticle() {
		NewsArticle a = article();
		when(articles.findById(7L)).thenReturn(Optional.of(a));
		when(analyzer.analyze(eq("AAPL surges"), eq("summary"), eq(List.of("AAPL"))))
				.thenReturn(new SentimentAnalysis(SentimentLabel.BULLISH, 0.7, 0.6));

		agent.handle(event(7));

		ArgumentCaptor<NewsArticle> saved = ArgumentCaptor.forClass(NewsArticle.class);
		verify(articles).save(saved.capture());
		assertEquals(SentimentLabel.BULLISH, saved.getValue().getSentimentLabel());
		assertEquals(0, saved.getValue().getSentimentScore().compareTo(new java.math.BigDecimal("0.700")));
		assertNotNull(saved.getValue().getAnalyzedAt());
	}

	@Test
	void skipsAlreadyAnalyzedArticle() {
		NewsArticle a = article();
		a.applySentiment(SentimentAnalysis.neutral(), Instant.now());
		when(articles.findById(7L)).thenReturn(Optional.of(a));

		agent.handle(event(7));

		verify(analyzer, never()).analyze(anyString(), anyString(), any());
		verify(articles, never()).save(any());
	}

	@Test
	void missingArticleIsANoOp() {
		when(articles.findById(7L)).thenReturn(Optional.empty());
		agent.handle(event(7));
		verify(articles, never()).save(any());
	}

	@Test
	void missingArticleIdIsANoOp() {
		agent.handle(event(null));
		verify(articles, never()).findById(any());
		verify(articles, never()).save(any());
	}
}
