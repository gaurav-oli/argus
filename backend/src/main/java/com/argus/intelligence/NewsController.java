package com.argus.intelligence;

import java.time.Instant;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Curated news queue for the dashboard (session-gated under {@code /api/news}). The UI shows one card
 * at a time: {@code GET /next} returns the current highest-impact ready card plus how many are queued,
 * and {@code POST /{id}/done} deletes that card once read and hands back the next one. Cards are
 * produced and kept fresh by {@link NewsCurationService}; this controller only reads and dismisses.
 */
@RestController
@RequestMapping("/api/news")
public class NewsController {

	private final NewsCardRepository cards;

	public NewsController(NewsCardRepository cards) {
		this.cards = cards;
	}

	/** The current card to read (or null), with the ready/pending counts for the queue badge. */
	@GetMapping("/next")
	@Transactional(readOnly = true)
	public NewsFeed next() {
		return feed();
	}

	/** Mark the current card read: delete it and return the next one plus updated counts. */
	@PostMapping("/{id}/done")
	@Transactional
	public ResponseEntity<NewsFeed> done(@PathVariable Long id) {
		cards.deleteById(id);
		cards.flush();
		return ResponseEntity.ok(feed());
	}

	private NewsFeed feed() {
		NewsCardView card = cards.findFirstBySummaryIsNotNullOrderByImpactScoreDesc()
				.map(NewsCardView::from)
				.orElse(null);
		return new NewsFeed(card, (int) cards.countBySummaryIsNotNull(), (int) cards.countBySummaryIsNull());
	}

	/**
	 * @param card      the card to read now, or null when the queue is empty
	 * @param remaining ready-to-read cards, including this one (the queue count)
	 * @param pending   cards still being summarized in the background
	 */
	public record NewsFeed(NewsCardView card, int remaining, int pending) {
	}

	public record NewsCardView(Long id, String headline, String summary, String source, String url,
			List<String> tickers, Instant publishedAt, Instant fetchedAt, Instant generatedAt, boolean fallback) {

		static NewsCardView from(NewsCard c) {
			return new NewsCardView(c.getId(), c.getHeadline(), c.getSummary(), c.getSource(), c.getUrl(),
					List.of(c.getTickers()), c.getPublishedAt(), c.getFetchedAt(), c.getGeneratedAt(),
					c.isFallback());
		}
	}
}
