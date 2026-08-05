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
	private final BreakingNewsAlertService breakingNews = mock(BreakingNewsAlertService.class);
	private final MacroKeywordMissRepository macroMisses = mock(MacroKeywordMissRepository.class);
	private final NewsSentimentAgent agent = new NewsSentimentAgent(articles, analyzer, breakingNews, macroMisses);

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
				.thenReturn(new SentimentAnalysis(SentimentLabel.BULLISH, 0.7, 0.6, false));

		agent.handle(event(7));

		ArgumentCaptor<NewsArticle> saved = ArgumentCaptor.forClass(NewsArticle.class);
		verify(articles).save(saved.capture());
		assertEquals(SentimentLabel.BULLISH, saved.getValue().getSentimentLabel());
		assertEquals(0, saved.getValue().getSentimentScore().compareTo(new java.math.BigDecimal("0.700")));
		assertNotNull(saved.getValue().getAnalyzedAt());
	}

	@Test
	void llmMacroClassificationTagsTheArticleEvenWithNoKeywordMatch() {
		// The exact gap an LLM pass closes: a genuinely macro/geopolitical story with none of
		// MacroRelevanceTagger's listed keywords still ends up tagged MACRO, via the model's judgment.
		NewsArticle a = article();
		when(articles.findById(7L)).thenReturn(Optional.of(a));
		when(analyzer.analyze(eq("AAPL surges"), eq("summary"), eq(List.of("AAPL"))))
				.thenReturn(new SentimentAnalysis(SentimentLabel.NEUTRAL, 0.0, 0.2, true));

		agent.handle(event(7));

		ArgumentCaptor<NewsArticle> saved = ArgumentCaptor.forClass(NewsArticle.class);
		verify(articles).save(saved.capture());
		assertEquals(List.of("AAPL", MacroRelevanceTagger.MACRO_TAG), List.of(saved.getValue().getTickers()));
	}

	@Test
	void nonMacroClassificationLeavesTickersUntouched() {
		NewsArticle a = article();
		when(articles.findById(7L)).thenReturn(Optional.of(a));
		when(analyzer.analyze(eq("AAPL surges"), eq("summary"), eq(List.of("AAPL"))))
				.thenReturn(new SentimentAnalysis(SentimentLabel.BULLISH, 0.7, 0.6, false));

		agent.handle(event(7));

		ArgumentCaptor<NewsArticle> saved = ArgumentCaptor.forClass(NewsArticle.class);
		verify(articles).save(saved.capture());
		assertEquals(List.of("AAPL"), List.of(saved.getValue().getTickers()));
	}

	@Test
	void llmOnlyMacroCatchIsLoggedAsAMissForLearning() {
		// The keyword list didn't tag it (article() starts with just ["AAPL"]) but the LLM caught it —
		// exactly the evidence MacroKeywordLearningService's weekly review needs.
		NewsArticle a = article();
		when(articles.findById(7L)).thenReturn(Optional.of(a));
		when(analyzer.analyze(eq("AAPL surges"), eq("summary"), eq(List.of("AAPL"))))
				.thenReturn(new SentimentAnalysis(SentimentLabel.NEUTRAL, 0.0, 0.2, true));

		agent.handle(event(7));

		ArgumentCaptor<MacroKeywordMiss> missCaptor = ArgumentCaptor.forClass(MacroKeywordMiss.class);
		verify(macroMisses).save(missCaptor.capture());
		assertEquals(7L, missCaptor.getValue().getArticleId());
		assertEquals("AAPL surges", missCaptor.getValue().getHeadline());
	}

	@Test
	void keywordAlreadyCaughtMacroIsNotLoggedAsAMiss() {
		// The keyword tagger already caught it at ingest time — the LLM agreeing isn't a "miss", so no
		// evidence should be logged (it wasn't a gap in the list).
		NewsArticle a = new NewsArticle("finnhub", "x1", "http://x", "Trump announces new tariffs", "summary",
				Instant.now(), new String[] {MacroRelevanceTagger.MACRO_TAG});
		when(articles.findById(7L)).thenReturn(Optional.of(a));
		when(analyzer.analyze(eq("Trump announces new tariffs"), eq("summary"), eq(List.of(MacroRelevanceTagger.MACRO_TAG))))
				.thenReturn(new SentimentAnalysis(SentimentLabel.NEUTRAL, 0.0, 0.2, true));

		agent.handle(event(7));

		verify(macroMisses, never()).save(any());
	}

	@Test
	void nonMacroClassificationDoesNotLogAMiss() {
		NewsArticle a = article();
		when(articles.findById(7L)).thenReturn(Optional.of(a));
		when(analyzer.analyze(eq("AAPL surges"), eq("summary"), eq(List.of("AAPL"))))
				.thenReturn(new SentimentAnalysis(SentimentLabel.BULLISH, 0.7, 0.6, false));

		agent.handle(event(7));

		verify(macroMisses, never()).save(any());
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
