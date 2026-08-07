package com.argus.intelligence;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface NewsCardRepository extends JpaRepository<NewsCard, Long> {

	/** The next card to show: highest-impact card that already has a summary. */
	Optional<NewsCard> findFirstBySummaryIsNotNullOrderByImpactScoreDesc();

	/** Every ready-to-read card, most important first — backs the carousel view. */
	List<NewsCard> findBySummaryIsNotNullOrderByImpactScoreDesc();

	/** Every ready-to-read card published since {@code cutoff}, most important first — the carousel
	 * view's "today and yesterday only" guarantee, enforced at read time rather than trusting the
	 * curation prune cycle (which only runs every 30 min). */
	List<NewsCard> findBySummaryIsNotNullAndPublishedAtAfterOrderByImpactScoreDesc(Instant cutoff);

	/** As above, but just the single highest-impact card — backs the one-at-a-time reader. */
	Optional<NewsCard> findFirstBySummaryIsNotNullAndPublishedAtAfterOrderByImpactScoreDesc(Instant cutoff);

	/** The next card to generate: highest-impact card still awaiting its paragraph. */
	Optional<NewsCard> findFirstBySummaryIsNullOrderByImpactScoreDesc();

	/** Ready-to-read cards (what the queue count shows). */
	long countBySummaryIsNotNull();

	/** Ready-to-read cards published since {@code cutoff} — the filtered queue's own count, so the
	 * badge never shows a number larger than what's actually in the (age-filtered) list. */
	long countBySummaryIsNotNullAndPublishedAtAfter(Instant cutoff);

	/** Cards still being summarized (drives the "more on the way" hint). */
	long countBySummaryIsNull();

	boolean existsByArticleId(Long articleId);

	/** Article ids already promoted to a card, to skip them during curation. */
	@Query("select c.articleId from NewsCard c")
	List<Long> findAllArticleIds();

	/** Prune cards whose underlying article has aged out of the freshness window. */
	@Modifying
	@Transactional
	@Query("delete from NewsCard c where c.publishedAt < :cutoff")
	int deleteByPublishedAtBefore(@Param("cutoff") Instant cutoff);
}
