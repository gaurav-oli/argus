package com.argus.intelligence;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** Persistence for {@link SourceCredibility} records (Story 4.3). */
public interface SourceCredibilityRepository extends JpaRepository<SourceCredibility, Long> {

	Optional<SourceCredibility> findBySource(String source);

	/** All sources ranked most-credible first — for the Intelligence view. */
	List<SourceCredibility> findAllByOrderByScoreDesc();
}
