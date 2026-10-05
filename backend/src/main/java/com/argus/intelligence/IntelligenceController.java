package com.argus.intelligence;

import com.argus.security.CurrentUserContext;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
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
	private final BreakingAlertRepository breaking;
	private final BreakingAlertReadRepository breakingReads;
	private final MacroKeywordLearningService macroKeywordLearning;
	private final long breakingRetentionDays;

	public IntelligenceController(NewsArticleRepository articles, SourceCredibilityRepository sources,
			StrangerAlertRepository strangers, BreakingAlertRepository breaking,
			BreakingAlertReadRepository breakingReads, MacroKeywordLearningService macroKeywordLearning,
			@Value("${argus.breaking-alerts.retention-days:3}") long breakingRetentionDays) {
		this.articles = articles;
		this.sources = sources;
		this.strangers = strangers;
		this.breaking = breaking;
		this.breakingReads = breakingReads;
		this.macroKeywordLearning = macroKeywordLearning;
		this.breakingRetentionDays = breakingRetentionDays;
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

	/** Ready-to-read breaking alerts (summarized, non-duplicate, within the retention window, and not
	 * yet dismissed by THIS signed-in person) for the carousel, plus how many are still being
	 * curated. Each person has their own "Done Reading" state over the same shared alerts. */
	@GetMapping("/breaking")
	@Transactional(readOnly = true)
	public BreakingQueue breaking() {
		Instant cutoff = Instant.now().minus(Duration.ofDays(breakingRetentionDays));
		Set<Long> readByMe = Set.copyOf(breakingReads.findAlertIdsByUserId(CurrentUserContext.get()));
		List<BreakingItem> ready = breaking
				.findBySummaryIsNotNullAndDuplicateFalseAndCreatedAtAfterOrderByCreatedAtDesc(cutoff).stream()
				.filter(a -> !readByMe.contains(a.getId()))
				.map(BreakingItem::from)
				.toList();
		return new BreakingQueue(ready, (int) breaking.countBySummaryIsNullAndDuplicateFalse());
	}

	/** "Done Reading" — dismiss for THIS person only (the shared alert, and everyone else's view of
	 * it, are untouched). Tolerates an id that's already gone or already dismissed by this person,
	 * for parity with the news carousel's done endpoint. */
	@PostMapping("/breaking/{id}/done")
	@Transactional
	public ResponseEntity<BreakingQueue> breakingDone(@PathVariable Long id) {
		Long userId = CurrentUserContext.get();
		if (breaking.existsById(id) && !breakingReads.existsByAlertIdAndUserId(id, userId)) {
			breakingReads.save(new BreakingAlertRead(id, userId));
		}
		return ResponseEntity.ok(breaking());
	}

	/** Manual trigger (rather than waiting up to a week) for Agent 8's keyword-learning review — mirrors
	 * the graduation resume endpoint's on-demand pattern. */
	@PostMapping("/macro-keywords/review")
	public MacroKeywordLearningService.Result reviewMacroKeywords() {
		return macroKeywordLearning.review();
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

	/** A breaking-news alert that was pushed: what fired, why, when, and (once curated) the
	 * beginner-friendly explanation of what it means. */
	public record BreakingItem(Long id, String headline, String url, List<String> tickers, String reason,
			String sentimentLabel, String summary, Instant createdAt) {

		static BreakingItem from(BreakingAlert a) {
			return new BreakingItem(a.getId(), a.getHeadline(), a.getUrl(),
					a.getTickers() == null ? List.of() : Arrays.asList(a.getTickers()),
					a.getReason(), a.getSentimentLabel(), a.getSummary(), a.getCreatedAt());
		}
	}

	/** @param alerts ready-to-read alerts, most recent first; @param pending alerts still being curated */
	public record BreakingQueue(List<BreakingItem> alerts, int pending) {
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
