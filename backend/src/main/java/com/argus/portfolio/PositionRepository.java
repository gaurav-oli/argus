package com.argus.portfolio;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/** Persistence for portfolio {@link Position} holdings (Story 3.1). */
public interface PositionRepository extends JpaRepository<Position, Long> {

	List<Position> findAllByOrderByTickerAsc();
}
