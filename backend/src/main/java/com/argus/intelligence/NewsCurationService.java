package com.argus.intelligence;

import com.argus.model.ModelGateway;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Builds and maintains the Dashboard's curated news queue ({@link NewsCard}s). Two best-effort
 * background steps keep a small buffer of ready-to-read cards so reading is instant:
 *
 * <ol>
 *   <li><b>Curate</b> — prune cards whose article has aged past the freshness window, then, if the
 *       buffer is below target, promote the most important recent articles (relevance × |sentiment|,
 *       above a relevance floor so unrelated noise is dropped) into new pending cards. This is cheap
 *       (no model calls) and portfolio-agnostic — it surfaces market-wide important news, not just
 *       tickers the user holds.</li>
 *   <li><b>Generate</b> — take the single highest-impact pending card and ask the local model
 *       ({@link ModelGateway}, BIG tier) for a paragraph on what happened and its market impact. One
 *       per tick keeps it friendly to the shared big-model semaphore; a model failure falls back to a
 *       deterministic paragraph so a card never gets stuck at the head of the queue.</li>
 * </ol>
 *
 * Everything is freshness-bounded: only articles published within {@code lookback-hours} are ever
 * promoted, and cards are pruned once their article ages out — the queue never shows stale news.
 */
@Service
public class NewsCurationService {

	private static final Logger log = LoggerFactory.getLogger(NewsCurationService.class);

	private final NewsArticleRepository articles;
	private final NewsCardRepository cards;
	private final ModelGateway gateway;
	private final long lookbackHours;
	private final int targetReady;
	private final double minRelevance;

	public NewsCurationService(NewsArticleRepository articles, NewsCardRepository cards, ModelGateway gateway,
			@Value("${argus.news-cards.lookback-hours:24}") long lookbackHours,
			@Value("${argus.news-cards.target-ready:5}") int targetReady,
			@Value("${argus.news-cards.min-relevance:0.30}") double minRelevance) {
		this.articles = articles;
		this.cards = cards;
		this.gateway = gateway;
		this.lookbackHours = lookbackHours;
		this.targetReady = targetReady;
		this.minRelevance = minRelevance;
	}

	// ----- step 1: curate (cheap, no model) -----

	@Scheduled(fixedDelayString = "${argus.news-cards.curate-interval-ms:1800000}",
			initialDelayString = "${argus.news-cards.curate-initial-delay-ms:15000}")
	public void scheduledCurate() {
		try {
			curate();
		} catch (RuntimeException ex) {
			log.warn("News curation failed: {}", ex.getMessage());
		}
	}

	/**
	 * Prune aged-out cards, then top up the buffer with fresh candidates. Each repository write manages
	 * its own transaction (the prune delete and each save), so this is safe to call directly from the
	 * self-invoked scheduler without a surrounding proxy transaction.
	 */
	public void curate() {
		int pruned = cards.deleteByPublishedAtBefore(freshnessCutoff());
		if (pruned > 0) {
			log.info("Pruned {} stale news card(s)", pruned);
		}

		int have = (int) (cards.countBySummaryIsNotNull() + cards.countBySummaryIsNull());
		int want = targetReady - have;
		if (want <= 0) {
			return;
		}

		Set<Long> alreadyCarded = new HashSet<>(cards.findAllArticleIds());
		List<NewsArticle> candidates = articles.findByPublishedAtAfterOrderByPublishedAtDesc(freshnessCutoff())
				.stream()
				.filter(a -> a.getSentimentLabel() != null)
				.filter(a -> relevance(a) >= minRelevance)
				.filter(a -> !alreadyCarded.contains(a.getId()))
				.sorted(Comparator.comparingDouble(NewsCurationService::impact).reversed())
				.limit(want)
				.toList();

		candidates.forEach(a -> cards.save(new NewsCard(a, impact(a))));
		if (!candidates.isEmpty()) {
			log.info("Curated {} new news card(s) awaiting summary", candidates.size());
		}
	}

	// ----- step 2: generate one pending summary (model call) -----

	@Scheduled(fixedDelayString = "${argus.news-cards.generate-interval-ms:20000}",
			initialDelayString = "${argus.news-cards.generate-initial-delay-ms:30000}")
	public void scheduledGenerate() {
		try {
			generateNextPending();
		} catch (RuntimeException ex) {
			log.warn("News summary generation failed: {}", ex.getMessage());
		}
	}

	/** Summarize the single highest-impact pending card, if any. Not transactional over the model call. */
	public void generateNextPending() {
		cards.deleteByPublishedAtBefore(freshnessCutoff());
		NewsCard card = cards.findFirstBySummaryIsNullOrderByImpactScoreDesc().orElse(null);
		if (card == null) {
			return;
		}
		NewsArticle article = articles.findById(card.getArticleId()).orElse(null);
		String paragraph = summarize(card, article);
		card.summarize(paragraph);
		cards.save(card);
		log.info("Generated news summary for card {} ({})", card.getId(), card.getHeadline());
	}

	private String summarize(NewsCard card, NewsArticle article) {
		try {
			String parsed = clean(gateway.generate(prompt(card, article)));
			if (parsed != null && !parsed.isBlank()) {
				return parsed;
			}
		} catch (RuntimeException ex) {
			log.warn("News-summary model call failed ({}) — using deterministic fallback", ex.getMessage());
		}
		return fallback(card, article);
	}

	private String prompt(NewsCard card, NewsArticle article) {
		String snippet = article != null && article.getSummary() != null ? article.getSummary() : "";
		String tickers = card.getTickers().length > 0 ? String.join(", ", card.getTickers()) : "none tagged";
		String sentiment = article != null && article.getSentimentLabel() != null
				? article.getSentimentLabel().name().toLowerCase() : "unclear";
		return """
				You are Argus, a friendly investing coach writing for a complete beginner who has no \
				finance background. Explain the news item below in plain, everyday language that a curious \
				15-year-old could follow. Keep sentences short and simple, and avoid jargon wherever you can.

				Write your answer in TWO parts separated by a line containing only "KEY TERMS:".

				Part 1 — ONE paragraph of 4-6 sentences: what happened, why it matters, and how it could \
				affect the stock market and the companies or industries involved. If you must use a financial \
				or technical word, explain it in plain words right there in parentheses.

				Part 2 — after the "KEY TERMS:" line, list 2-4 financial or technical terms from the story that \
				a beginner might not know. Put ONE per line, written exactly as "Term — a short, simple \
				definition in everyday words." If the story genuinely has no such terms, write just "None".

				No hype, no disclaimers, no markdown, no preamble. Start directly with the paragraph.

				HEADLINE: %s
				SOURCE: %s
				TICKERS: %s
				DETECTED SENTIMENT: %s
				SNIPPET: %s
				""".formatted(card.getHeadline(), card.getSource(), tickers, sentiment, snippet);
	}

	/** Model output may include stray fences/preamble; keep it a clean, bounded paragraph + glossary. */
	private static String clean(String raw) {
		if (raw == null) {
			return null;
		}
		String s = raw.replace("```", "").strip();
		return s.length() <= 1800 ? s : s.substring(0, 1800).strip();
	}

	private static String fallback(NewsCard card, NewsArticle article) {
		String snippet = article != null && article.getSummary() != null ? article.getSummary().strip() : "";
		String tickers = card.getTickers().length > 0
				? " Tickers in focus: " + String.join(", ", card.getTickers()) + "." : "";
		String base = "%s (via %s).".formatted(card.getHeadline(), card.getSource());
		return snippet.isBlank() ? base + tickers : base + " " + snippet + tickers;
	}

	// ----- ranking helpers (shared shape with the market pulse) -----

	private Instant freshnessCutoff() {
		return Instant.now().minus(Duration.ofHours(lookbackHours));
	}

	private static double relevance(NewsArticle a) {
		return a.getRelevanceScore() == null ? 0.0 : a.getRelevanceScore().doubleValue();
	}

	private static double impact(NewsArticle a) {
		double relevance = a.getRelevanceScore() == null ? 0.5 : a.getRelevanceScore().doubleValue();
		double sentiment = a.getSentimentScore() == null ? 0 : Math.abs(a.getSentimentScore().doubleValue());
		return relevance * (0.25 + sentiment); // floor so a strongly-relevant neutral story still ranks
	}
}
