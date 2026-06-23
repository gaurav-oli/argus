package com.argus.portfolio;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Persistence for {@link CorporateAction}s (Story 3.3). */
public interface CorporateActionRepository extends JpaRepository<CorporateAction, Long> {

	List<CorporateAction> findAllByOrderByCreatedAtDesc();

	/**
	 * Load an action under a row write-lock for confirm/dismiss, so two concurrent calls can't both
	 * read {@code pending} and double-apply (mirrors the import-confirm lock in Stories 3.1/3.2).
	 */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select a from CorporateAction a where a.id = :id")
	Optional<CorporateAction> findByIdForUpdate(@Param("id") Long id);
}
