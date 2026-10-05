package com.argus.ops;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class AgentCadenceTest {

	@Test
	void looksUpByAgentId() {
		assertThat(AgentCadence.forAgent("news")).contains(AgentCadence.NEWS);
		assertThat(AgentCadence.forAgent("filings-reader")).contains(AgentCadence.FILINGS_READER);
	}

	@Test
	void agentsWithoutAFixedCadenceHaveNone() {
		// Agent 9 runs only on request; Agent 6 is continuous. Neither may ever read as stalled.
		assertThat(AgentCadence.forAgent("research")).isEmpty();
		assertThat(AgentCadence.forAgent("cost")).isEmpty();
		assertThat(AgentCadence.forAgent("nope")).isEmpty();
	}

	@Test
	void everyStaleThresholdLeavesRoomForAtLeastOneMissedCycle() {
		// A single skipped run must never flip an agent to stalled.
		assertThat(AgentCadence.values()).allSatisfy(c ->
				assertThat(c.staleAfter()).isGreaterThan(c.interval()));
	}

	@Test
	void agentIdsAreUnique() {
		assertThat(Arrays.stream(AgentCadence.values()).map(AgentCadence::agentId).distinct().count())
				.isEqualTo(AgentCadence.values().length);
	}

	@Test
	void freshnessThresholdsAreUnchangedByTheMove() {
		// These were FreshnessService's literals before the values moved here; the alert must not shift.
		assertThat(AgentCadence.NEWS.staleAfter()).isEqualTo(Duration.ofMinutes(30));
		assertThat(AgentCadence.SOCIAL.staleAfter()).isEqualTo(Duration.ofHours(1));
		assertThat(AgentCadence.INTERNET.staleAfter()).isEqualTo(Duration.ofHours(12));
		assertThat(AgentCadence.FILINGS.staleAfter()).isEqualTo(Duration.ofHours(72));
		assertThat(AgentCadence.RECOMMENDER.staleAfter()).isEqualTo(Duration.ofHours(12));
		assertThat(AgentCadence.CALENDAR.staleAfter()).isEqualTo(Duration.ofHours(72));
	}
}
