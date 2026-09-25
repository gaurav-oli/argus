package com.argus.learning;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LearningReportRepository extends JpaRepository<LearningReport, Long> {

	Optional<LearningReport> findFirstByOrderByCreatedAtDesc();
}
