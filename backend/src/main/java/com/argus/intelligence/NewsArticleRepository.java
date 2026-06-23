package com.argus.intelligence;

import java.time.Instant;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Persistence for ingested {@link NewsArticle} rows (Story 4.1). */
public interface NewsArticleRepository extends JpaRepository<NewsArticle, Long> {

	/** Dedup guard: an article with this upstream natural key already exists. */
	boolean existsBySourceAndExternalId(String source, String externalId);

	/** Articles published since {@code since}, newest first — the Stranger Danger scan window (Story 4.4). */
	List<NewsArticle> findByPublishedAtAfterOrderByPublishedAtDesc(Instant since);

	/** Most-recent articles for the Intelligence view feed. */
	List<NewsArticle> findTop50ByOrderByPublishedAtDesc();

	/** Analyzed articles tagged with {@code ticker} since {@code since} — Agent 5's news signal (Story 6.4). */
	@Query(value = """
			SELECT * FROM news_articles
			WHERE :ticker = ANY(tickers) AND analyzed_at IS NOT NULL AND published_at > :since
			ORDER BY published_at DESC""", nativeQuery = true)
	List<NewsArticle> findAnalyzedForTicker(@Param("ticker") String ticker, @Param("since") Instant since);
}
