package com.argus.intelligence;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** Persistence for {@link StrangerAlert} rows (Story 4.4). */
public interface StrangerAlertRepository extends JpaRepository<StrangerAlert, Long> {

	Optional<StrangerAlert> findByTicker(String ticker);
}
