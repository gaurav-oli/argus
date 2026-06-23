package com.argus.recommendation;

import org.springframework.data.jpa.repository.JpaRepository;

/** Persistence for the single {@link AgentGraduation} row (Story 6.6). */
public interface AgentGraduationRepository extends JpaRepository<AgentGraduation, Integer> {
}
