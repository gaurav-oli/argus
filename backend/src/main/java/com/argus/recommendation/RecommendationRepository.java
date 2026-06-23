package com.argus.recommendation;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** Persistence for {@link Recommendation} aggregates (Story 6.2). */
public interface RecommendationRepository extends JpaRepository<Recommendation, Long> {

	/** Recommendations newest-first for the feed/UI. */
	List<Recommendation> findTop50ByOrderByCreatedAtDesc();

	/** A single recommendation with its diagnostic signals eagerly loaded. */
	@org.springframework.data.jpa.repository.EntityGraph(attributePaths = "signals")
	Optional<Recommendation> findWithSignalsById(Long id);
}
