package com.argus.deepanalysis;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DeepVerdictModelSnapshotRepository extends JpaRepository<DeepVerdictModelSnapshot, Long> {

	List<DeepVerdictModelSnapshot> findAllByOrderByComputedAtAsc();
}
