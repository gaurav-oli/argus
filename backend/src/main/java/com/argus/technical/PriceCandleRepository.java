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

	/** Every stored trading date for a ticker — the dedupe set for an ingest (the old top-200 window
	 * would miss older rows once a year of history is stored, and re-inserting them violates the unique key). */
	@Query("select c.candleDate from PriceCandle c where c.ticker = :ticker")
	java.util.Set<java.time.LocalDate> datesFor(@org.springframework.data.repository.query.Param("ticker") String ticker);

	long countByTicker(String ticker);

	/** Up to a year+ of history, most-recent-first — what {@link ChartReader} needs for a 200-day average. */
	List<PriceCandle> findTop300ByTickerOrderByCandleDateDesc(String ticker);

	/** Most-recent N candles, most-recent-first — callers needing chronological order (indicator
	 * math) must reverse this themselves, same convention as every other "recent N" query in the app. */
	List<PriceCandle> findTop200ByTickerOrderByCandleDateDesc(String ticker);

	/** Newest stored trading date for a ticker (null when it has no history) — drives the staleness
	 * ordering that decides which tickers {@link CandleIngestionService} refreshes first. */
	@Query("select max(c.candleDate) from PriceCandle c where c.ticker = :ticker")
	java.time.LocalDate latestDateFor(@org.springframework.data.repository.query.Param("ticker") String ticker);

	/** Most-recent ingest time — Agent 10 "last run" (Agents dashboard). */
	@Query("select max(c.ingestedAt) from PriceCandle c")
	Instant latestIngestedAt();
}
