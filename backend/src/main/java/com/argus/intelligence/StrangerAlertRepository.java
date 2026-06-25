package com.argus.intelligence;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

/** Persistence for {@link StrangerAlert} rows (Story 4.4). */
public interface StrangerAlertRepository extends JpaRepository<StrangerAlert, Long> {

	Optional<StrangerAlert> findByTicker(String ticker);

	/** Active stranger alerts, highest pump-and-dump risk first — for the Intelligence view. */
	List<StrangerAlert> findAllByOrderByRiskScoreDesc();

	/** Most-recent detection — Agent 4 "last run" (Operations dashboard). */
	@Query("select max(s.detectedAt) from StrangerAlert s")
	Instant latestDetectedAt();
}
