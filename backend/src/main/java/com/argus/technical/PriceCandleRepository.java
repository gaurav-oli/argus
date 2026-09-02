package com.argus.technical;

import java.time.Instant;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

/** Persistence for {@link PriceCandle} rows (Agent 10 — Technical Analysis). */
public interface PriceCandleRepository extends JpaRepository<PriceCandle, Long> {

	boolean existsByTickerAndCandleDate(String ticker, java.time.LocalDate candleDate);

	/** Whether this ticker has ANY history yet — decides a one-time bulk backfill vs. an
	 * incremental daily fetch in {@link CandleIngestionService}. */
	boolean existsByTicker(String ticker);

	/** Most-recent N candles, most-recent-first — callers needing chronological order (indicator
	 * math) must reverse this themselves, same convention as every other "recent N" query in the app. */
	List<PriceCandle> findTop200ByTickerOrderByCandleDateDesc(String ticker);

	/** Most-recent ingest time — Agent 10 "last run" (Agents dashboard). */
	@Query("select max(c.ingestedAt) from PriceCandle c")
	Instant latestIngestedAt();
}
