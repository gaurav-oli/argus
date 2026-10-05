package com.argus.intelligence;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Persistence for per-user breaking-alert dismissals ({@link BreakingAlertRead}). */
public interface BreakingAlertReadRepository extends JpaRepository<BreakingAlertRead, Long> {

	boolean existsByAlertIdAndUserId(Long alertId, Long userId);

	/** Every alert id this person has dismissed — used to filter the shared queue down to THEIR
	 * own unread set, without needing a join in the main query. */
	@Query("select r.alertId from BreakingAlertRead r where r.userId = :userId")
	List<Long> findAlertIdsByUserId(@Param("userId") Long userId);
}
