package com.argus.intelligence;

import java.time.Instant;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/** Persistence for ingested {@link NewsArticle} rows (Story 4.1). */
public interface NewsArticleRepository extends JpaRepository<NewsArticle, Long> {

	/** Dedup guard: an article with this upstream natural key already exists. */
	boolean existsBySourceAndExternalId(String source, String externalId);

	/** Articles published since {@code since}, newest first — the Stranger Danger scan window (Story 4.4). */
	List<NewsArticle> findByPublishedAtAfterOrderByPublishedAtDesc(Instant since);
}
