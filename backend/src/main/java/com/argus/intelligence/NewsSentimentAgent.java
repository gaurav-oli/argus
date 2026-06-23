package com.argus.intelligence;

import com.argus.agent.Agent;
import com.argus.agent.EventEnvelope;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Agent 1's sentiment stage (Story 4.2, FR-8). Consumes {@code news.article.ingested} events from the
 * ingestion pipeline (Story 4.1), runs the small-model {@link SentimentAnalyzer} over each article,
 * and persists the sentiment + relevance scores. Idempotent: an already-analyzed article is skipped,
 * so a redelivered (previously-failed) event never double-scores.
 */
@Component
public class NewsSentimentAgent implements Agent {

	private static final Logger log = LoggerFactory.getLogger(NewsSentimentAgent.class);

	private final NewsArticleRepository articles;
	private final SentimentAnalyzer analyzer;

	public NewsSentimentAgent(NewsArticleRepository articles, SentimentAnalyzer analyzer) {
		this.articles = articles;
		this.analyzer = analyzer;
	}

	@Override
	public String name() {
		return "news-sentiment-agent";
	}

	@Override
	public String streamKey() {
		return NewsIngestionService.STREAM_KEY;
	}

	@Override
	@Transactional
	public void handle(EventEnvelope event) {
		Long articleId = asLong(event.payload().get("articleId"));
		if (articleId == null) {
			log.warn("news sentiment: event {} missing articleId", event.eventId());
			return;
		}
		NewsArticle article = articles.findById(articleId).orElse(null);
		if (article == null) {
			log.debug("news sentiment: article {} not found (skipping)", articleId);
			return;
		}
		if (article.isAnalyzed()) {
			return; // idempotent on redelivery
		}
		List<String> tickers = article.getTickers() == null ? List.of() : Arrays.asList(article.getTickers());
		SentimentAnalysis analysis = analyzer.analyze(article.getHeadline(), article.getSummary(), tickers);
		article.applySentiment(analysis, Instant.now());
		articles.save(article);
		log.debug("news sentiment: article {} -> {} (score={}, relevance={})",
				articleId, analysis.label(), analysis.score(), analysis.relevance());
	}

	private static Long asLong(Object value) {
		return (value instanceof Number n) ? n.longValue() : null;
	}
}
