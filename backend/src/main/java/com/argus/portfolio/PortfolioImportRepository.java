package com.argus.portfolio;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Persistence for staged {@link PortfolioImport} batches (Story 3.1). */
public interface PortfolioImportRepository extends JpaRepository<PortfolioImport, Long> {

	/**
	 * Load an import for confirmation under a row write-lock, so the read-status →
	 * write-positions → mark-confirmed sequence is atomic against concurrent confirms (a second
	 * confirm blocks here, then sees {@code confirmed} and is rejected — no double-commit).
	 */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select p from PortfolioImport p where p.id = :id")
	Optional<PortfolioImport> findByIdForUpdate(@Param("id") Long id);

	/** Imports still awaiting a manual look — the automatic path (Adaptive parser) stages here
	 * instead of auto-confirming whenever it couldn't reach full confidence. */
	List<PortfolioImport> findByStatusOrderByCreatedAtDesc(String status);
}
