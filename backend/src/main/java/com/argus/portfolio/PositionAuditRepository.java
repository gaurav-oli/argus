package com.argus.portfolio;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/** Persistence for the manual-edit {@link PositionAudit} trail (Story 3.7). */
public interface PositionAuditRepository extends JpaRepository<PositionAudit, Long> {

	List<PositionAudit> findTop50ByOrderByCreatedAtDesc();
}
