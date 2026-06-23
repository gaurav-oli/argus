package com.argus.intelligence;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** Persistence for {@link StrangerAlert} rows (Story 4.4). */
public interface StrangerAlertRepository extends JpaRepository<StrangerAlert, Long> {

	Optional<StrangerAlert> findByTicker(String ticker);

	/** Active stranger alerts, highest pump-and-dump risk first — for the Intelligence view. */
	List<StrangerAlert> findAllByOrderByRiskScoreDesc();
}
