package com.argus.recommendation;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RecommendationDebateRepository extends JpaRepository<RecommendationDebate, Long> {

	List<RecommendationDebate> findByRecommendationIdOrderByCreatedAtDesc(Long recommendationId);
}
