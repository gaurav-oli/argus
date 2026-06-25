package com.argus.portfolio;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/** Persistence for portfolio {@link Position} holdings (Story 3.1). */
public interface PositionRepository extends JpaRepository<Position, Long> {

	List<Position> findAllByOrderByTickerAsc();

	List<Position> findByTicker(String ticker);

	/** Existing holdings for a bank — the reconcile scope on re-import (multi-bank holdings). */
	List<Position> findByInstitution(String institution);
}
