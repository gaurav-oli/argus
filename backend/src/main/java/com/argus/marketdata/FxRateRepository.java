package com.argus.marketdata;

import org.springframework.data.jpa.repository.JpaRepository;

/** Persistence for the {@link FxRate} historical-rate cache (Story 3.2). */
public interface FxRateRepository extends JpaRepository<FxRate, FxRate.Key> {
}
