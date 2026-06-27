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

	/** Projection for {@link #aggregateByAgent()}. */
	interface AgentWeightAggregate {
		String getAgent();

		BigDecimal getTotalWeight();

		long getSignalCount();
	}
}
