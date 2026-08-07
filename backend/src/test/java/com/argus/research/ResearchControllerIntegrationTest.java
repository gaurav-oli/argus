package com.argus.research;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.argus.TestcontainersConfiguration;
import com.argus.common.BadRequestException;
import com.argus.common.NotFoundException;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * Agent 9's full lifecycle against real Postgres (dev profile, so the model gateway runs against the
 * local dev mock rather than a real LLM — the exact JSON-driven plan/replan behavior is covered by
 * {@link ResearchAgentServiceTest}'s mocked-gateway unit tests; this test verifies the real wiring
 * (repository queries, WebSocket-adjacent status transitions, controller endpoints) reaches a terminal
 * state end to end).
 *
 * <p>Every test that starts a job awaits it to a terminal state before returning — the pipeline's
 * executor is a singleton bean shared across every test method in this (cached) Spring context, so an
 * un-awaited background job would still be mutating its row when the next test's {@link #clean()}
 * deletes it out from under it (confirmed the hard way: an earlier version of this test left jobs
 * running past their test method and got sporadic "row already updated or deleted" failures).
 */
@SpringBootTest
@ActiveProfiles("dev")
@Import(TestcontainersConfiguration.class)
class ResearchControllerIntegrationTest {

	@Autowired
	ResearchController controller;

	@Autowired
	ResearchJobRepository jobs;

	@BeforeEach
	void clean() {
		jobs.deleteAll();
	}

	@Test
	void startingAJobReturnsImmediatelyInPlanningOrLaterState() {
		ResearchAgentService.ResearchJobView started = controller.start(new ResearchController.StartRequest("AAPL"));

		assertEquals("AAPL", started.ticker());
		assertNotNull(started.id());
		// PLANNING is the state right after insert; the background pipeline may have already advanced
		// it by the time this assertion runs, so accept any real, non-blank status rather than racing it.
		assertNotNull(started.status());

		awaitTerminal(started.id()); // drain before returning — see class doc
	}

	@Test
	void aStartedJobReachesATerminalStateWithoutHanging() {
		ResearchAgentService.ResearchJobView started = controller.start(new ResearchController.StartRequest("MSFT"));

		ResearchAgentService.ResearchJobView finalView = awaitTerminal(started.id());

		assertTrue(finalView.status().equals("DONE") || finalView.status().equals("FAILED"),
				"the pipeline must always reach a terminal state, never hang: was " + finalView.status());
		if (finalView.status().equals("DONE")) {
			assertNotNull(finalView.report(), "a DONE job must have a report, even a fallback one");
		}
	}

	@Test
	void rejectsAMalformedTicker() {
		assertThrows(BadRequestException.class,
				() -> controller.start(new ResearchController.StartRequest("not a ticker")));
	}

	@Test
	void listReturnsMostRecentJobsFirst() {
		ResearchAgentService.ResearchJobView first = controller.start(new ResearchController.StartRequest("AAA"));
		ResearchAgentService.ResearchJobView second = controller.start(new ResearchController.StartRequest("BBB"));

		var list = controller.list();

		assertEquals(2, list.size());
		assertEquals("BBB", list.get(0).ticker(), "most recently created first");

		awaitTerminal(first.id());
		awaitTerminal(second.id());
	}

	@Test
	void getReturns404ForAnUnknownJob() {
		assertThrows(NotFoundException.class, () -> controller.get(999_999L));
	}

	/** Polls {@code GET /jobs/{id}} until the job reaches DONE/FAILED, or fails the test after a
	 * generous timeout — the local dev-mock model responds fast, so a real run should finish in
	 * seconds, not the full timeout budget. */
	private ResearchAgentService.ResearchJobView awaitTerminal(Long jobId) {
		// Generous: reliably ~1s in isolation, but a full-suite run shares the JVM/thread pool/DB
		// connections with hundreds of other tests, and this class's job is sometimes the very first
		// real background job to run against a freshly-migrated Testcontainers Postgres in that shared
		// context — under that contention a short deadline is a false-negative risk, not a real hang.
		Instant deadline = Instant.now().plus(Duration.ofSeconds(60));
		ResearchAgentService.ResearchJobView lastSeen = null;
		while (Instant.now().isBefore(deadline)) {
			lastSeen = controller.get(jobId);
			if (lastSeen.status().equals("DONE") || lastSeen.status().equals("FAILED")) {
				return lastSeen;
			}
			try {
				Thread.sleep(200);
			}
			catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
				fail("Interrupted while awaiting job completion");
			}
		}
		fail("Job " + jobId + " did not reach a terminal state within the test timeout — last seen status was "
				+ (lastSeen == null ? "null (never even fetched)" : lastSeen.status())
				+ ", plan=" + (lastSeen == null ? "n/a" : lastSeen.plan()));
		return null; // unreachable
	}
}
