package com.argus.filings;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface FilingDigestRepository extends JpaRepository<FilingDigest, Long> {

	@Query("select max(d.createdAt) from FilingDigest d")
	java.time.Instant latestCreatedAt();

	boolean existsByAccession(String accession);

	List<FilingDigest> findTop12ByTickerOrderByFiledAtDesc(String ticker);

	Optional<FilingDigest> findFirstByTickerAndKindOrderByFiledAtDesc(String ticker, String kind);

	/** The newest digest per ticker (any kind) for the overview list. */
	@Query(value = "select distinct on (ticker) * from filing_digest order by ticker, filed_at desc, id desc", nativeQuery = true)
	List<FilingDigest> latestPerTicker();
}
