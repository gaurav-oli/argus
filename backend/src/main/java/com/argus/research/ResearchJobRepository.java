package com.argus.research;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/** Persistence for {@link ResearchJob} rows (Agent 9). */
public interface ResearchJobRepository extends JpaRepository<ResearchJob, Long> {

	/** Most recent jobs first, for the job list view. */
	List<ResearchJob> findTop20ByOrderByCreatedAtDesc();

	/** Jobs in any of the given (non-terminal) statuses — Agent 9's ACTIVE/IDLE status. */
	long countByStatusIn(List<ResearchJob.Status> statuses);

	/** Completed jobs — Agent 9's "captured" count on the fleet card. */
	long countByStatus(ResearchJob.Status status);
}
