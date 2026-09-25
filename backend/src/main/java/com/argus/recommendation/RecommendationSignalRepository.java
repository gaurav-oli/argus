package com.argus.recommendation;

import java.math.BigDecimal;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

/** Persistence + aggregation for {@link RecommendationSignal} diagnostic rows (Story 9.3). */
public interface RecommendationSignalRepository extends JpaRepository<RecommendationSignal, Long> {

	/** Total weight + signal count per agent across all recommendations — drives contribution % (Story 9.3). */
	@Query("""
			select s.agent as agent, sum(s.weight) as totalWeight, count(s) as signalCount
			from RecommendationSignal s
			group by s.agent""")
	List<AgentWeightAggregate> aggregateByAgent();

	/** (recommendation_id, agent, direction, weight) rows for a set of recommendations — lets the Trade Learner
	 * reconstruct what older calls rested on without loading each recommendation's lazy signal list. */
	@Query(value = "select recommendation_id, agent, direction, weight from recommendation_signals where recommendation_id in (:ids)",
			nativeQuery = true)
	List<Object[]> rowsFor(@org.springframework.data.repository.query.Param("ids") java.util.Collection<Long> ids);

	/** Projection for {@link #aggregateByAgent()}. */
	interface AgentWeightAggregate {
		String getAgent();

		BigDecimal getTotalWeight();

		long getSignalCount();
	}
}
