package com.argus.persona;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/** Persistence for cached {@link PersonaVerdict}s (F11). */
public interface PersonaVerdictRepository extends JpaRepository<PersonaVerdict, Long> {

	List<PersonaVerdict> findByRecommendationIdOrderByPersona(Long recommendationId);

	boolean existsByRecommendationId(Long recommendationId);
}
