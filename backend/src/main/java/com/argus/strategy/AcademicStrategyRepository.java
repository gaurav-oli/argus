package com.argus.strategy;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface AcademicStrategyRepository extends JpaRepository<AcademicStrategy, String> {

	List<AcademicStrategy> findByStatus(String status);

	List<AcademicStrategy> findByComputableTrue();

	long countByKind(String kind);

	long countByStatus(String status);

	@Query("select s from AcademicStrategy s where s.status = 'ACTIVE' order by s.acronym")
	List<AcademicStrategy> active();

	@Query("select max(s.importedAt) from AcademicStrategy s")
	java.time.Instant lastImportedAt();
}
