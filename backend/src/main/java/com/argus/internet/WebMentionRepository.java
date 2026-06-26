package com.argus.internet;

import java.time.Instant;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

/** Persistence for {@link WebMention} rows (Agent 3). */
public interface WebMentionRepository extends JpaRepository<WebMention, Long> {

	boolean existsBySourceAndExternalId(String source, String externalId);

	/** Recent mentions for a ticker (both sources) — Agent 5's internet signal input. */
	List<WebMention> findByTickerAndPostedAtAfter(String ticker, Instant after);

	/** All recent mentions (both sources) — the per-ticker buzz aggregate. */
	List<WebMention> findByPostedAtAfter(Instant after);

	/** Most-recent Hacker News stories across tickers (Intelligence feed). */
	List<WebMention> findTop30BySourceOrderByPostedAtDesc(String source);

	/** Most-recent ingest time — Agent 3 "last run" (Operations dashboard). */
	@Query("select max(m.ingestedAt) from WebMention m")
	Instant latestIngestedAt();
}
