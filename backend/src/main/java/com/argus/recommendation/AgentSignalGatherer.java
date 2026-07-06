package com.argus.recommendation;

import com.argus.calendar.EarningsQuietPeriodService;
import com.argus.calendar.QuietPeriodStatus;
import com.argus.intelligence.NewsArticle;
import com.argus.intelligence.NewsArticleRepository;
import com.argus.intelligence.SentimentLabel;
import com.argus.internet.WebMention;
import com.argus.internet.WebMentionRepository;
import com.argus.sec.SecFiling;
import com.argus.sec.SecFilingRepository;
import com.argus.social.SocialPostRepository;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
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

	private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(AgentSignalGatherer.class);

	private static final Duration NEWS_WINDOW = Duration.ofDays(7);
	private static final int FULL_COVERAGE_ARTICLES = 5;
	private static final Duration SOCIAL_WINDOW = Duration.ofDays(3);
	private static final int SOCIAL_MIN_POSTS = 5;
	private static final int SOCIAL_FULL_VOLUME = 30;
	/** Crowd sentiment is noisier than curated news — cap its max influence below Agent 1's. */
	private static final double SOCIAL_MAX_WEIGHT = 0.6;

	private static final int INSIDER_WINDOW_DAYS = 30;

	private static final Duration INTERNET_WINDOW = Duration.ofDays(14);
	private static final Duration INTERNET_RECENT = Duration.ofDays(3);
	private static final double ATTENTION_SPIKE = 1.3;
	/** Internet buzz is the noisiest, lowest-ROI source — hard-cap its influence below all others. */
	private static final double INTERNET_MAX_WEIGHT = 0.35;

	private final NewsArticleRepository news;
	private final SocialPostRepository social;
	private final SecFilingRepository sec;
	private final WebMentionRepository web;
	private final EarningsQuietPeriodService quietPeriod;
	private final AdaptiveTuningService tuning;

	public AgentSignalGatherer(NewsArticleRepository news, SocialPostRepository social,
			SecFilingRepository sec, WebMentionRepository web, EarningsQuietPeriodService quietPeriod,
			AdaptiveTuningService tuning) {
		this.news = news;
		this.social = social;
		this.sec = sec;
		this.web = web;
		this.quietPeriod = quietPeriod;
		this.tuning = tuning;
	}

	public List<AgentSignal> gather(String ticker) {
		List<AgentSignal> signals = new ArrayList<>();
		newsSignal(ticker).ifPresent(signals::add);
		socialSignal(ticker).ifPresent(signals::add);
		insiderSignal(ticker).ifPresent(signals::add);
		internetSignal(ticker).ifPresent(signals::add);
		calendarSignal(ticker).ifPresent(signals::add);
		// Phase B: scale each agent's weight by its learned reliability (identity when tuning is disabled).
		List<AgentSignal> tuned = signals.stream().map(this::applyReliability).toList();
		log.info("gather({}) → [{}]", ticker,
				tuned.stream().map(AgentSignal::agent).reduce((a, b) -> a + "," + b).orElse("NONE"));
		return tuned;
	}

	/** Multiply a signal's weight by its agent's learned reliability multiplier. */
	private AgentSignal applyReliability(AgentSignal s) {
		double mult = tuning.weightMultiplier(s.agent());
		if (mult == 1.0) {
			return s;
		}
		return new AgentSignal(s.agent(), s.direction(), s.weight() * mult, s.rationale());
	}

	/**
	 * Agent 3 — internet attention. Direction from Hacker News story sentiment (when present); a
	 * Wikipedia pageview spike over baseline adds conviction. Capped well below the other agents —
	 * it's broad-web noise, useful mainly as corroboration.
	 */
	private Optional<AgentSignal> internetSignal(String ticker) {
		List<WebMention> recent = web.findByTickerAndPostedAtAfter(ticker, Instant.now().minus(INTERNET_WINDOW));
		if (recent.isEmpty()) {
			return Optional.empty();
		}
		long hn = recent.stream().filter(m -> "hackernews".equals(m.getSource())).count();
		long bull = recent.stream().filter(m -> m.getSentimentLabel() == SentimentLabel.BULLISH).count();
		long bear = recent.stream().filter(m -> m.getSentimentLabel() == SentimentLabel.BEARISH).count();

		Instant recentCut = Instant.now().minus(INTERNET_RECENT);
		List<WebMention> wiki = recent.stream().filter(m -> "wikipedia".equals(m.getSource())).toList();
		double recentAvg = wiki.stream().filter(m -> m.getPostedAt().isAfter(recentCut))
				.mapToLong(WebMention::getScore).average().orElse(0);
		double baseAvg = wiki.stream().filter(m -> !m.getPostedAt().isAfter(recentCut))
				.mapToLong(WebMention::getScore).average().orElse(0);
		double ratio = baseAvg > 0 ? recentAvg / baseAvg : 0;
		boolean spike = ratio >= ATTENTION_SPIKE;

		long scored = bull + bear;
		if (scored == 0 && !spike) {
			return Optional.empty(); // attention is steady and undirected — nothing to add
		}
		double net = scored == 0 ? 0 : (double) (bull - bear) / scored;
		SignalDirection dir = net > 0.15 ? SignalDirection.BULLISH
				: net < -0.15 ? SignalDirection.BEARISH : SignalDirection.NEUTRAL;
		double conviction = Math.min(1.0, hn / 10.0) * Math.abs(net);
		double spikeBoost = spike ? Math.min(1.0, ratio - 1.0) : 0;
		double weight = Math.min(INTERNET_MAX_WEIGHT, 0.1 + 0.2 * conviction + 0.1 * spikeBoost);
		String rationale = String.format("Web buzz: %d HN stories (%d↑/%d↓)%s", hn, bull, bear,
				spike ? String.format(", Wikipedia attention %.1f× baseline", ratio) : "");
		return Optional.of(new AgentSignal("agent-3-internet", dir, weight, rationale));
	}

	/**
	 * Agent 4 — insider (Form 4) activity. Open-market PURCHASES are a strong bullish signal (rare,
	 * high conviction); SALES are common and routine, so they read mildly bearish at low weight.
	 */
	private Optional<AgentSignal> insiderSignal(String ticker) {
		List<SecFiling> filings = sec.findByTickerAndTransactionTypeInAndFiledAtAfter(ticker,
				List.of("BUY", "SELL"), LocalDate.now().minusDays(INSIDER_WINDOW_DAYS));
		long buys = filings.stream().filter(f -> "BUY".equals(f.getTransactionType())).count();
		long sells = filings.stream().filter(f -> "SELL".equals(f.getTransactionType())).count();
		if (buys == 0 && sells == 0) {
			return Optional.empty();
		}
		if (buys > 0) {
			double weight = Math.min(0.8, 0.4 + 0.2 * buys);
			String rationale = buys + " insider purchase(s) in " + INSIDER_WINDOW_DAYS + "d"
					+ (sells > 0 ? ", " + sells + " sale(s)" : "");
			return Optional.of(new AgentSignal("agent-4-financial", SignalDirection.BULLISH, weight, rationale));
		}
		double weight = Math.min(0.35, 0.1 + 0.05 * sells); // sales are noisy → low influence
		return Optional.of(new AgentSignal("agent-4-financial", SignalDirection.BEARISH, weight,
				sells + " insider sale(s) in " + INSIDER_WINDOW_DAYS + "d (often routine)"));
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
