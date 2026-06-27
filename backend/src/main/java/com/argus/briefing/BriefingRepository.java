package com.argus.briefing;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** Persistence for {@link Briefing} rows (Epic 8, FR-16). */
public interface BriefingRepository extends JpaRepository<Briefing, Long> {

	/** The most recently generated briefing — what the dashboard card and morning push surface. */
	Optional<Briefing> findFirstByOrderByGeneratedAtDesc();
}
