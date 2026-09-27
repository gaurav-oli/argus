package com.argus.learning;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LearnedRuleRepository extends JpaRepository<LearnedRule, Long> {

	long countByStatus(LearnedRule.Status status);

	List<LearnedRule> findByStatusOrderByActivatedAtDesc(LearnedRule.Status status);

	List<LearnedRule> findByStatusIn(java.util.Collection<LearnedRule.Status> statuses);

	List<LearnedRule> findTop50ByOrderByCreatedAtDesc();
}
