package com.argus.portfolio;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

/** Persistence for portfolio {@link Position} holdings (Story 3.1). */
public interface PositionRepository extends JpaRepository<Position, Long> {

	List<Position> findAllByOrderByTickerAsc();

	List<Position> findByTicker(String ticker);

	/** Existing holdings for a bank — the reconcile scope on re-import (multi-bank holdings). */
	List<Position> findByInstitution(String institution);

	/**
	 * The one deliberate exception to per-user isolation (Phase 2): every ticker SYMBOL anyone holds,
	 * across ALL users, for the shared Agents/Intelligence watchlist — never share counts or cost
	 * basis, which stay behind {@code @TenantId} like everywhere else. A plain scalar native query
	 * bypasses Hibernate's entity-level {@code @TenantId} filtering on purpose (it loads no {@link
	 * Position} entities at all, so there is nothing for the tenant filter to restrict); it also works
	 * from a background job with no signed-in user on the thread, unlike every other query here.
	 */
	@Query(value = "select distinct ticker from positions", nativeQuery = true)
	List<String> allTickersAcrossAllUsers();

	/**
	 * As {@link #allTickersAcrossAllUsers()}, but also carrying each ticker's company name (also public,
	 * non-financial information) — for Agent 3's internet-ingestion search queries, which run as a
	 * background job with no signed-in user on the thread and need the same cross-user escape hatch.
	 */
	@Query(value = "select ticker as ticker, company_name as companyName from positions", nativeQuery = true)
	List<TickerCompany> allTickerCompanyPairsAcrossAllUsers();

	/** Native-query projection for {@link #allTickerCompanyPairsAcrossAllUsers()}. */
	interface TickerCompany {
		String getTicker();

		String getCompanyName();
	}
}
