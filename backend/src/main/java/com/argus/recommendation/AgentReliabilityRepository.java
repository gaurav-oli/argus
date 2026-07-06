package com.argus.recommendation;

import org.springframework.data.jpa.repository.JpaRepository;

/** Persistence for per-agent realized reliability ({@link AgentReliability}), Phase B. */
public interface AgentReliabilityRepository extends JpaRepository<AgentReliability, String> {
}
