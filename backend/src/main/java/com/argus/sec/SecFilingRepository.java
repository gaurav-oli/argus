package com.argus.sec;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

/** Persistence for {@link SecFiling} rows (Agent 4). */
public interface SecFilingRepository extends JpaRepository<SecFiling, Long> {

	boolean existsByAccession(String accession);

	/** Most-recent filings across tickers (Intelligence view feed). */
	List<SecFiling> findTop50ByOrderByFiledAtDesc();

	/** Insider transactions for a ticker since a date — Agent 5's signal input. */
	List<SecFiling> findByTickerAndTransactionTypeInAndFiledAtAfter(String ticker, List<String> types,
			LocalDate after);

	/** Most-recent ingest time — Agent 4 "last run" (Operations dashboard). */
	@Query("select max(f.ingestedAt) from SecFiling f")
	Instant latestIngestedAt();
}
