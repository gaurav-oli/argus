package com.argus.intelligence;

import org.springframework.data.jpa.repository.JpaRepository;

/** Persistence for ingested {@link NewsArticle} rows (Story 4.1). */
public interface NewsArticleRepository extends JpaRepository<NewsArticle, Long> {

	/** Dedup guard: an article with this upstream natural key already exists. */
	boolean existsBySourceAndExternalId(String source, String externalId);
}
