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

	/** The next card to generate: highest-impact card still awaiting its paragraph. */
	Optional<NewsCard> findFirstBySummaryIsNullOrderByImpactScoreDesc();

	/** Ready-to-read cards (what the queue count shows). */
	long countBySummaryIsNotNull();

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
