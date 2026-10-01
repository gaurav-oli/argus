package com.argus.deepanalysis;

import java.time.Instant;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface DeepScorecardSnapshotRepository extends JpaRepository<DeepScorecardSnapshot, Long> {

	List<DeepScorecardSnapshot> findByVerdictAndHorizonDaysOrderByComputedAtAsc(String verdict, int horizonDays);

	List<DeepScorecardSnapshot> findAllByOrderByComputedAtAsc();

	@Query("select max(s.computedAt) from DeepScorecardSnapshot s")
	Instant latestComputedAt();
}
