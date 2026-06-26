package com.argus.social;

import java.time.Instant;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

/** Persistence for {@link SocialPost} rows (Agent 2). */
public interface SocialPostRepository extends JpaRepository<SocialPost, Long> {

	/** Dedup guard: a post with this source-native id already exists. */
	boolean existsBySourceAndExternalId(String source, String externalId);

	/** Most-recent posts for a ticker (Intelligence view feed). */
	List<SocialPost> findTop50ByTickerOrderByPostedAtDesc(String ticker);

	/** Most-recent posts across all tickers (Intelligence view feed). */
	List<SocialPost> findTop50ByOrderByPostedAtDesc();

	/** Most-recent ingest time — Agent 2 "last run" (Operations dashboard). */
	@Query("select max(p.ingestedAt) from SocialPost p")
	Instant latestIngestedAt();

	/** Per-ticker, per-sentiment counts since {@code since} — the social-sentiment aggregate. */
	@Query("""
			select p.ticker, p.sentimentLabel, count(p)
			from SocialPost p where p.postedAt > :since
			group by p.ticker, p.sentimentLabel""")
	List<Object[]> sentimentCountsSince(Instant since);

	/** Per-sentiment counts for one ticker since {@code since} — Agent 5's social signal input. */
	@Query("""
			select p.sentimentLabel, count(p)
			from SocialPost p where p.ticker = :ticker and p.postedAt > :since
			group by p.sentimentLabel""")
	List<Object[]> sentimentCountsForTicker(String ticker, Instant since);
}
