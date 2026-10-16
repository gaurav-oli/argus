package com.argus.filings;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface FilingDigestRepository extends JpaRepository<FilingDigest, Long> {

	@Query("select max(d.createdAt) from FilingDigest d")
	java.time.Instant latestCreatedAt();

	/** Agent 14 "last activity" for the Agents page: its last completed run, or its newest digest if later.
	 * Not just the newest digest — no tracked company files for a week or more between earnings seasons, and a
	 * healthy reader with nothing to read showed as stalled (2026-10-16). The run is stamped by the cron ledger. */
	@Query(value = "select greatest((select max(created_at) from filing_digest), (select last_run_at from agent_runs "
			+ "where agent_id = 'job:com.argus.filings.FilingDigestService.refreshUniverse'))", nativeQuery = true)
	java.time.Instant latestActivityAt();

	boolean existsByAccession(String accession);

	List<FilingDigest> findTop12ByTickerOrderByFiledAtDesc(String ticker);

	Optional<FilingDigest> findFirstByTickerAndKindOrderByFiledAtDesc(String ticker, String kind);

	/** The newest digest per ticker (any kind) for the overview list. */
	@Query(value = "select distinct on (ticker) * from filing_digest order by ticker, filed_at desc, id desc", nativeQuery = true)
	List<FilingDigest> latestPerTicker();
}
