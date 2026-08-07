package com.argus.intelligence;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Curated news queue for the dashboard (session-gated under {@code /api/news}). {@code GET /next}
 * returns the current highest-impact ready card plus how many are queued (the original one-at-a-time
 * reader); {@code GET /queue} returns every ready card at once for the carousel view. Both share the
 * same underlying cards — {@code POST /{id}/done} deletes a card once read regardless of which view
 * it was read from. Cards are produced and kept fresh by {@link NewsCurationService}; this controller
 * only reads and dismisses.
 *
 * <p>Both read endpoints additionally enforce a "today or yesterday" freshness floor at request time
 * ({@link #todayAndYesterdayCutoff()}) — {@link NewsCurationService}'s prune only runs every 30 min, so
 * without this a card could still be served up to that long after aging out of its lookback window.
 */
@RestController
@RequestMapping("/api/news")
public class NewsController {

	/** Same timezone convention used app-wide for calendar-day logic (earnings quiet periods, digest,
	 * cost governor bands) — see {@code EarningsQuietPeriodService}, {@code NotificationPreferencesService}. */
	private static final ZoneId ZONE = ZoneId.of("America/Toronto");

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

	/** Every ready-to-read card at once (richest first), for the carousel view — today and yesterday only. */
	@GetMapping("/queue")
	@Transactional(readOnly = true)
	public NewsQueue queue() {
		Instant cutoff = todayAndYesterdayCutoff();
		List<NewsCardView> ready = cards.findBySummaryIsNotNullAndPublishedAtAfterOrderByImpactScoreDesc(cutoff)
				.stream()
				.map(NewsCardView::from)
				.toList();
		return new NewsQueue(ready, (int) cards.countBySummaryIsNull());
	}

	/** Midnight at the start of yesterday, in the app's timezone — "today or yesterday" as a real
	 * calendar-day boundary rather than a rolling 24-48h window. */
	private static Instant todayAndYesterdayCutoff() {
		return LocalDate.now(ZONE).minusDays(1).atStartOfDay(ZONE).toInstant();
	}

	/** Mark a card read: delete it and return the next single-card view plus updated counts. Tolerates
	 * a card that's already gone (e.g. pruned for staleness between a carousel render and this click) —
	 * that's the same end state as a successful delete, not an error. */
	@PostMapping("/{id}/done")
	@Transactional
	public ResponseEntity<NewsFeed> done(@PathVariable Long id) {
		try {
			cards.deleteById(id);
			cards.flush();
		}
		catch (EmptyResultDataAccessException ignored) {
			// already gone — treat as success
		}
		return ResponseEntity.ok(feed());
	}

	private NewsFeed feed() {
		Instant cutoff = todayAndYesterdayCutoff();
		NewsCardView card = cards.findFirstBySummaryIsNotNullAndPublishedAtAfterOrderByImpactScoreDesc(cutoff)
				.map(NewsCardView::from)
				.orElse(null);
		return new NewsFeed(card, (int) cards.countBySummaryIsNotNullAndPublishedAtAfter(cutoff),
				(int) cards.countBySummaryIsNull());
	}

	/**
	 * @param card      the card to read now, or null when the queue is empty
	 * @param remaining ready-to-read cards, including this one (the queue count)
	 * @param pending   cards still being summarized in the background
	 */
	public record NewsFeed(NewsCardView card, int remaining, int pending) {
	}

	/** @param cards every ready card, most important first; @param pending cards still being summarized */
	public record NewsQueue(List<NewsCardView> cards, int pending) {
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
