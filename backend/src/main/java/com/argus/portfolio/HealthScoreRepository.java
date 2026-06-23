package com.argus.portfolio;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** Persistence for the daily {@link HealthScore} series (Stories 3.8/3.9). */
public interface HealthScoreRepository extends JpaRepository<HealthScore, Long> {

	Optional<HealthScore> findByScoredOn(LocalDate scoredOn);

	List<HealthScore> findByScoredOnGreaterThanEqualOrderByScoredOnAsc(LocalDate from);
}
