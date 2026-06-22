package com.argus.portfolio;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/** Persistence for {@link PositionLot}s (Story 3.2). */
public interface PositionLotRepository extends JpaRepository<PositionLot, Long> {

	List<PositionLot> findByPositionIdOrderByTradeDateAsc(Long positionId);
}
