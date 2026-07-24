package com.argus.calendar;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

/** Persistence for {@link CompanyLogo} rows. */
public interface CompanyLogoRepository extends JpaRepository<CompanyLogo, String> {

	/** Batch lookup for the calendar UI — one query per request instead of one per event. */
	List<CompanyLogo> findAllByTickerIn(Collection<String> tickers);

	/** Tickers already cached (hit or confirmed miss) — used to skip re-fetching on ingest. */
	@Query("select c.ticker from CompanyLogo c where c.ticker in :tickers")
	Set<String> findCachedTickers(Collection<String> tickers);
}
