package com.argus.ops;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.argus.TestcontainersConfiguration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/** The real application's cron jobs, as the boot catch-up sees them. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class MissedRunCatchUpIntegrationTest {

	@Autowired
	MissedRunCatchUp catchUp;

	@Autowired
	JdbcTemplate jdbc;

	@Test
	void everyJobTheMigrationSeedsIsARealCronJobUnderThatExactName() {
		Set<String> discovered = catchUp.cronJobs().stream().map(MissedRunCatchUp.Job::key).collect(Collectors.toSet());
		List<String> seeded = jdbc.queryForList(
				"select substring(agent_id from 5) from agent_runs where agent_id like 'job:%'", String.class);
		Set<String> unknown = new HashSet<>(seeded);
		unknown.removeAll(discovered);
		assertTrue(unknown.isEmpty(), "seeded keys with no matching @Scheduled(cron) method: " + unknown);
		assertTrue(discovered.contains("com.argus.briefing.BriefingService.scheduledBriefing"));
	}

	@Test
	void jobsWithTheirOwnCatchUpAreLeftToIt() {
		assertTrue(catchUp.cronJobs().stream().noneMatch(j -> j.key().startsWith("com.argus.recommendation.RecommendationTrigger.")));
	}
}
