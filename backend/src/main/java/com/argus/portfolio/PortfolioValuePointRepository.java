package com.argus.portfolio;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** Persistence for the daily {@link PortfolioValuePoint} series (Story 3.6). */
public interface PortfolioValuePointRepository extends JpaRepository<PortfolioValuePoint, Long> {

	Optional<PortfolioValuePoint> findByCapturedOn(LocalDate capturedOn);

	List<PortfolioValuePoint> findByCapturedOnGreaterThanEqualOrderByCapturedOnAsc(LocalDate from);

	List<PortfolioValuePoint> findAllByOrderByCapturedOnAsc();
}
