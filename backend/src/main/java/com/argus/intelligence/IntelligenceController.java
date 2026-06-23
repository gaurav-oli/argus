package com.argus.intelligence;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read endpoints for Agent 1's intelligence outputs (Epic 4), session-gated under
 * {@code /api/intelligence}: the recent news feed with sentiment/relevance (Stories 4.1/4.2),
 * source credibility scores (Story 4.3), and active Stranger Danger alerts (Story 4.4). Resources
 * returned directly as camelCase JSON, matching the portfolio controllers.
 */
@RestController
@RequestMapping("/api/intelligence")
public class IntelligenceController {

	private final NewsArticleRepository articles;
	private final SourceCredibilityRepository sources;
	private final StrangerAlertRepository strangers;

	public IntelligenceController(NewsArticleRepository articles, SourceCredibilityRepository sources,
			StrangerAlertRepository strangers) {
		this.articles = articles;
		this.sources = sources;
		this.strangers = strangers;
	}

	@GetMapping("/news")
	public List<NewsItem> news() {
		return articles.findTop50ByOrderByPublishedAtDesc().stream().map(NewsItem::from).toList();
	}

	@GetMapping("/sources")
	public List<SourceItem> sources() {
		return sources.findAllByOrderByScoreDesc().stream().map(SourceItem::from).toList();
	}

	@GetMapping("/strangers")
	public List<StrangerItem> strangers() {
		return strangers.findAllByOrderByRiskScoreDesc().stream().map(StrangerItem::from).toList();
	}

	/** A news article with its Agent-1 sentiment/relevance scoring (null scores = not yet analyzed). */
	public record NewsItem(Long id, String source, String headline, String url, Instant publishedAt,
			List<String> tickers, String sentimentLabel, BigDecimal sentimentScore,
			BigDecimal relevanceScore, boolean analyzed) {

		static NewsItem from(NewsArticle a) {
			return new NewsItem(a.getId(), a.getSource(), a.getHeadline(), a.getUrl(), a.getPublishedAt(),
					a.getTickers() == null ? List.of() : Arrays.asList(a.getTickers()),
					a.getSentimentLabel() == null ? null : a.getSentimentLabel().name(),
					a.getSentimentScore(), a.getRelevanceScore(), a.isAnalyzed());
		}
	}

	/** A source's credibility score, tier band, and block state. */
	public record SourceItem(String source, int score, String tier, boolean blocked,
			int correctCount, int incorrectCount) {

		static SourceItem from(SourceCredibility c) {
			return new SourceItem(c.getSource(), c.getScore(), c.getTier().name(), c.isBlocked(),
					c.getCorrectCount(), c.getIncorrectCount());
		}
	}

	/** A flagged stranger ticker with its pump-and-dump risk and elevated consensus bar. */
	public record StrangerItem(String ticker, int riskScore, int coverageCount, int distinctSources,
			BigDecimal avgSourceScore, int requiredConsensus, Instant windowStart) {

		static StrangerItem from(StrangerAlert s) {
			return new StrangerItem(s.getTicker(), s.getRiskScore(), s.getCoverageCount(),
					s.getDistinctSources(), s.getAvgSourceScore(), s.getRequiredConsensus(),
					s.getWindowStart());
		}
	}
}
