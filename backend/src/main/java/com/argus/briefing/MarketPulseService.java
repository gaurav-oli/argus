package com.argus.briefing;

import com.argus.intelligence.NewsArticle;
import com.argus.intelligence.NewsArticleRepository;
import com.argus.model.ModelGateway;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The on-demand "market pulse" (Epic 8, FR-16 follow-up). On the dashboard's Refresh, it scans the
 * market-impacting news captured so far — recent, analyzed articles ranked by impact (relevance ×
 * |sentiment|) — and asks the local model ({@link ModelGateway}) for a short summary of what could
 * move the market. State is a single {@link MarketPulse} row: if no article newer than the last
 * pulse's newest has arrived, it skips the model and reports "nothing major since we last checked",
 * keeping the previous summary and its timestamp. The model call is best-effort — on failure it
 * falls back to a deterministic one-liner so Refresh never hard-fails.
 */
@Service
public class MarketPulseService {

	private static final Logger log = LoggerFactory.getLogger(MarketPulseService.class);
	private static final int MAX_ARTICLES = 12;

	private final NewsArticleRepository news;
	private final ModelGateway gateway;
	private final MarketPulseRepository pulses;
	private final long lookbackHours;

	public MarketPulseService(NewsArticleRepository news, ModelGateway gateway, MarketPulseRepository pulses,
			@Value("${argus.market-pulse.lookback-hours:48}") long lookbackHours) {
		this.news = news;
		this.gateway = gateway;
		this.pulses = pulses;
		this.lookbackHours = lookbackHours;
	}

	/** The current pulse, if one has ever been generated. */
	@Transactional(readOnly = true)
	public java.util.Optional<MarketPulse> current() {
		return pulses.findById(MarketPulse.SINGLETON_ID);
	}

	/** What a refresh produced: the pulse row plus whether it actually changed since last time. */
	public record Result(MarketPulse pulse, boolean hasUpdates) {
	}

	/**
	 * Re-scan recent market-impacting news and, if anything new arrived since the last pulse, regenerate
	 * the summary. Not {@code @Transactional}: the slow model call must not hold a DB connection, and the
	 * save is transactional on its own.
	 */
	public Result refresh() {
		MarketPulse existing = pulses.findById(MarketPulse.SINGLETON_ID).orElse(null);

		List<NewsArticle> impactful = topImpactful();
		Instant newest = impactful.stream()
				.map(NewsArticle::getPublishedAt)
				.filter(java.util.Objects::nonNull)
				.max(Comparator.naturalOrder())
				.orElse(null);

		// Nothing new since the last pulse's newest covered article → don't bother the model.
		if (existing != null && (newest == null
				|| (existing.getLatestArticleAt() != null && !newest.isAfter(existing.getLatestArticleAt())))) {
			return new Result(existing, false);
		}

		String summary = summarize(impactful);
		MarketPulse pulse = save(summary, impactful.size(), newest);
		log.info("Market pulse refreshed: {} article(s), newest {}", impactful.size(), newest);
		return new Result(pulse, true);
	}

	private MarketPulse save(String summary, int count, Instant newest) {
		MarketPulse pulse = pulses.findById(MarketPulse.SINGLETON_ID).orElse(null);
		if (pulse == null) {
			pulse = new MarketPulse(summary, count, newest);
		} else {
			pulse.update(summary, count, newest);
		}
		return pulses.save(pulse);
	}

	/** Recent, analyzed articles ranked by impact (relevance × |sentiment|), highest first. */
	private List<NewsArticle> topImpactful() {
		Instant since = Instant.now().minus(Duration.ofHours(lookbackHours));
		return news.findByPublishedAtAfterOrderByPublishedAtDesc(since).stream()
				.filter(a -> a.getSentimentLabel() != null)
				.sorted(Comparator.comparingDouble(MarketPulseService::impact).reversed())
				.limit(MAX_ARTICLES)
				.toList();
	}

	private static double impact(NewsArticle a) {
		double relevance = a.getRelevanceScore() == null ? 0.5 : a.getRelevanceScore().doubleValue();
		double sentiment = a.getSentimentScore() == null ? 0 : Math.abs(a.getSentimentScore().doubleValue());
		return relevance * (0.25 + sentiment); // floor so a strongly-relevant neutral story still ranks
	}

	private String summarize(List<NewsArticle> articles) {
		if (articles.isEmpty()) {
			return "No market-impacting news captured yet — check back after the agents' next cycle.";
		}
		try {
			String parsed = extract(gateway.generate(prompt(articles)));
			if (parsed != null && !parsed.isBlank()) {
				return parsed;
			}
		} catch (RuntimeException ex) {
			log.warn("Market-pulse model call failed ({}) — using deterministic fallback", ex.getMessage());
		}
		return fallback(articles);
	}

	private String prompt(List<NewsArticle> articles) {
		StringBuilder lines = new StringBuilder();
		articles.forEach(a -> lines.append("- [").append(a.getSentimentLabel().name().toLowerCase()).append("] ")
				.append(a.getHeadline())
				.append(a.getTickers() != null && a.getTickers().length > 0
						? " (" + String.join(", ", a.getTickers()) + ")" : "")
				.append('\n'));
		return """
				You are Argus, a calm personal investing assistant. Below are the most market-impacting news \
				headlines captured recently. Summarize, in 2-3 sentences, what could move the market and why — \
				group related items, lead with the most consequential. Warm but factual, no hype, no disclaimers, \
				no bullet points. If nothing looks materially significant, say so plainly.

				HEADLINES
				%s
				Respond with ONLY the summary text, no preamble, no markdown.
				""".formatted(lines);
	}

	/** Model output may include stray fences/preamble; keep it to a clean paragraph. */
	private static String extract(String raw) {
		if (raw == null) {
			return null;
		}
		String s = raw.replace("```", "").strip();
		return s.length() <= 600 ? s : s.substring(0, 600).strip();
	}

	private static String fallback(List<NewsArticle> articles) {
		long bullish = articles.stream().filter(a -> "BULLISH".equals(label(a))).count();
		long bearish = articles.stream().filter(a -> "BEARISH".equals(label(a))).count();
		String lead = articles.get(0).getHeadline();
		return "%d market-impacting stories captured (%d bullish, %d bearish). Most notable: %s"
				.formatted(articles.size(), bullish, bearish, lead);
	}

	private static String label(NewsArticle a) {
		return a.getSentimentLabel() == null ? "" : a.getSentimentLabel().name();
	}
}
