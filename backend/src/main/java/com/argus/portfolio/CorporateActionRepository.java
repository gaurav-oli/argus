package com.argus.portfolio;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/** Persistence for {@link CorporateAction}s (Story 3.3). */
public interface CorporateActionRepository extends JpaRepository<CorporateAction, Long> {

	List<CorporateAction> findAllByOrderByCreatedAtDesc();
}
