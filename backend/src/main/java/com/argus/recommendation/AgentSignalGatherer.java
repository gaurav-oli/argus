package com.argus.recommendation;

import com.argus.calendar.EarningsQuietPeriodService;
import com.argus.calendar.QuietPeriodStatus;
import com.argus.intelligence.NewsArticle;
import com.argus.intelligence.NewsArticleRepository;
import com.argus.intelligence.SentimentLabel;
import com.argus.social.SocialPostRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Assembles the {@link AgentSignal}s the scoring engine needs for a ticker (Story 6.4) from the
 * agents that currently exist: Agent 1 (news sentiment, weighted by coverage and relevance),
 * Agent 2 (social crowd sentiment, weighted by volume and conviction — capped lower since the crowd
 * is noisier), and Agent 7 (the earnings calendar). As more agents come online they add their
 * signals here; until then coverage is honestly low (lowering confidence).
 */
@Component
public class AgentSignalGatherer {

	private static final Duration NEWS_WINDOW = Duration.ofDays(7);
	private static final int FULL_COVERAGE_ARTICLES = 5;
	private static final Duration SOCIAL_WINDOW = Duration.ofDays(3);
	private static final int SOCIAL_MIN_POSTS = 5;
	private static final int SOCIAL_FULL_VOLUME = 30;
	/** Crowd sentiment is noisier than curated news — cap its max influence below Agent 1's. */
	private static final double SOCIAL_MAX_WEIGHT = 0.6;

	private final NewsArticleRepository news;
	private final SocialPostRepository social;
	private final EarningsQuietPeriodService quietPeriod;

	public AgentSignalGatherer(NewsArticleRepository news, SocialPostRepository social,
			EarningsQuietPeriodService quietPeriod) {
		this.news = news;
		this.social = social;
		this.quietPeriod = quietPeriod;
	}

	public List<AgentSignal> gather(String ticker) {
		List<AgentSignal> signals = new ArrayList<>();
		newsSignal(ticker).ifPresent(signals::add);
		socialSignal(ticker).ifPresent(signals::add);
		calendarSignal(ticker).ifPresent(signals::add);
		return signals;
	}

	/** Agent 2 — crowd sentiment: net bullish/bearish skew, weighted by post volume and conviction. */
	private Optional<AgentSignal> socialSignal(String ticker) {
		long bull = 0;
		long bear = 0;
		long total = 0;
		for (Object[] row : social.sentimentCountsForTicker(ticker, Instant.now().minus(SOCIAL_WINDOW))) {
			SentimentLabel label = (SentimentLabel) row[0];
			long count = ((Number) row[1]).longValue();
			total += count;
			if (label == SentimentLabel.BULLISH) {
				bull += count;
			}
			else if (label == SentimentLabel.BEARISH) {
				bear += count;
			}
		}
		long scored = bull + bear;
		if (total < SOCIAL_MIN_POSTS || scored == 0) {
			return Optional.empty(); // too little crowd signal to be meaningful
		}
		double net = (double) (bull - bear) / scored; // -1 (all bearish) .. +1 (all bullish)
		SignalDirection dir = net > 0.15 ? SignalDirection.BULLISH
				: net < -0.15 ? SignalDirection.BEARISH : SignalDirection.NEUTRAL;
		double volume = Math.min(1.0, (double) total / SOCIAL_FULL_VOLUME);
		double weight = SOCIAL_MAX_WEIGHT * volume * (0.4 + 0.6 * Math.abs(net));
		String rationale = String.format("Crowd: %d bullish / %d bearish of %d posts (net %+.0f%%)",
				bull, bear, total, net * 100);
		return Optional.of(new AgentSignal("agent-2-social", dir, weight, rationale));
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
