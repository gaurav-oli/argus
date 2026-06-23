package com.argus.recommendation;

import com.argus.calendar.EarningsQuietPeriodService;
import com.argus.calendar.QuietPeriodStatus;
import com.argus.intelligence.NewsArticle;
import com.argus.intelligence.NewsArticleRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Assembles the {@link AgentSignal}s the scoring engine needs for a ticker (Story 6.4) from the
 * agents that currently exist: Agent 1 (news sentiment, weighted by coverage and relevance) and
 * Agent 7 (the earnings calendar — a note-period adds bearish uncertainty). As more agents come
 * online they add their signals here; until then coverage is honestly low (lowering confidence).
 */
@Component
public class AgentSignalGatherer {

	private static final Duration NEWS_WINDOW = Duration.ofDays(7);
	private static final int FULL_COVERAGE_ARTICLES = 5;

	private final NewsArticleRepository news;
	private final EarningsQuietPeriodService quietPeriod;

	public AgentSignalGatherer(NewsArticleRepository news, EarningsQuietPeriodService quietPeriod) {
		this.news = news;
		this.quietPeriod = quietPeriod;
	}

	public List<AgentSignal> gather(String ticker) {
		List<AgentSignal> signals = new ArrayList<>();
		newsSignal(ticker).ifPresent(signals::add);
		calendarSignal(ticker).ifPresent(signals::add);
		return signals;
	}

	private Optional<AgentSignal> newsSignal(String ticker) {
		List<NewsArticle> articles = news.findAnalyzedForTicker(ticker, Instant.now().minus(NEWS_WINDOW));
		if (articles.isEmpty()) {
			return Optional.empty();
		}
		double avgSentiment = average(articles, NewsArticle::getSentimentScore);
		double avgRelevance = average(articles, NewsArticle::getRelevanceScore);
		SignalDirection dir = avgSentiment > 0.1 ? SignalDirection.BULLISH
				: avgSentiment < -0.1 ? SignalDirection.BEARISH : SignalDirection.NEUTRAL;
		double coverage = Math.min(1.0, (double) articles.size() / FULL_COVERAGE_ARTICLES);
		double weight = coverage * (0.5 + 0.5 * avgRelevance);
		String rationale = String.format("Avg news sentiment %.2f across %d article(s), relevance %.0f%%",
				avgSentiment, articles.size(), avgRelevance * 100);
		return Optional.of(new AgentSignal("agent-1-news", dir, weight, rationale));
	}

	private Optional<AgentSignal> calendarSignal(String ticker) {
		QuietPeriodStatus qp = quietPeriod.statusFor(ticker);
		if (qp.status() == QuietPeriodStatus.Status.NOTE) {
			return Optional.of(new AgentSignal("agent-7-calendar", SignalDirection.BEARISH, 0.3,
					"Earnings within " + qp.tradingDaysUntil() + " trading days — added uncertainty"));
		}
		return Optional.empty();
	}

	private static double average(List<NewsArticle> articles,
			java.util.function.Function<NewsArticle, java.math.BigDecimal> field) {
		return articles.stream().map(field).filter(java.util.Objects::nonNull)
				.mapToDouble(java.math.BigDecimal::doubleValue).average().orElse(0);
	}
}
