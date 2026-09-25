package com.argus.learning;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LearnedRuleRepository extends JpaRepository<LearnedRule, Long> {

	List<LearnedRule> findByStatusOrderByActivatedAtDesc(LearnedRule.Status status);

	List<LearnedRule> findByStatusIn(java.util.Collection<LearnedRule.Status> statuses);

	List<LearnedRule> findTop50ByOrderByCreatedAtDesc();
}
