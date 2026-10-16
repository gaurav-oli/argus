package com.argus.filings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.argus.TestcontainersConfiguration;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/** Agent 14's "last activity" against real Postgres: a quiet run counts, not only a new digest. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class FilingDigestRepositoryIntegrationTest {

	private static final String JOB = "job:com.argus.filings.FilingDigestService.refreshUniverse";

	@Autowired
	FilingDigestRepository repo;

	@Autowired
	JdbcTemplate jdbc;

	@BeforeEach
	void clean() {
		repo.deleteAll();
		jdbc.update("delete from agent_runs where agent_id = ?", JOB);
	}

	@Test
	void lastActivityIsTheLastRunEvenWhenNothingNewWasFiled() {
		assertNull(repo.latestActivityAt(), "never ran, nothing digested");

		Instant digested = Instant.now().minus(7, ChronoUnit.DAYS).truncatedTo(ChronoUnit.MICROS);
		jdbc.update("insert into filing_digest (ticker, accession, form, kind, filed_at, score, payload, created_at) "
				+ "values ('MU', 'acc-1', '10-K', 'ANNUAL_REPORT', current_date - 7, 0, '{}', ?)", Timestamp.from(digested));
		assertEquals(digested, repo.latestActivityAt(), "no recorded run yet: fall back to the newest digest");

		Instant ran = Instant.now().minus(2, ChronoUnit.HOURS).truncatedTo(ChronoUnit.MICROS);
		jdbc.update("insert into agent_runs (agent_id, last_run_at) values (?, ?)", JOB, Timestamp.from(ran));
		assertEquals(ran, repo.latestActivityAt(), "a run that found nothing new still counts as activity");
	}
}
