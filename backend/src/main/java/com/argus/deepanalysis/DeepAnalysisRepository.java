package com.argus.deepanalysis;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DeepAnalysisRepository extends JpaRepository<DeepAnalysis, Long> {

	/** The most recent finished analysis for a ticker — the one the recommender reads. */
	Optional<DeepAnalysis> findFirstByTickerAndStatusOrderByFinishedAtDesc(String ticker, DeepAnalysis.Status status);

	List<DeepAnalysis> findTop20ByTickerOrderByCreatedAtDesc(String ticker);

	List<DeepAnalysis> findByStatusIn(Collection<DeepAnalysis.Status> statuses);

	Optional<DeepAnalysis> findFirstByTickerAndStatusInOrderByCreatedAtDesc(String ticker, Collection<DeepAnalysis.Status> statuses);

	/** Newest row per ticker regardless of status (a running analysis shows its progress next to the last verdict). */
	@Query(value = "select distinct on (ticker) * from deep_analysis order by ticker, created_at desc, id desc", nativeQuery = true)
	List<DeepAnalysis> latestPerTicker();

	/** Newest DONE row per ticker — what the Intelligence page and the recommender show. */
	@Query(value = "select distinct on (ticker) * from deep_analysis where status = 'DONE' order by ticker, finished_at desc, id desc",
			nativeQuery = true)
	List<DeepAnalysis> latestDonePerTicker();

	@Query("select max(d.finishedAt) from DeepAnalysis d where d.ticker = :ticker and d.status = 'DONE'")
	Instant lastFinished(@Param("ticker") String ticker);

	long countByStatus(DeepAnalysis.Status status);
}
