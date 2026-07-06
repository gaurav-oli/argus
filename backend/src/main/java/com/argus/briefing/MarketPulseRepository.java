package com.argus.briefing;

import org.springframework.data.jpa.repository.JpaRepository;

/** Persistence for the singleton {@link MarketPulse} row (Epic 8, FR-16 follow-up). */
public interface MarketPulseRepository extends JpaRepository<MarketPulse, Short> {
}
