package com.argus.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.config.CronTask;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.scheduling.support.ScheduledMethodRunnable;

/** Which missed cron slots are caught up at startup, and which are deliberately left alone. */
class MissedRunCatchUpTest {

	private final ScheduledRunLedger ledger = mock(ScheduledRunLedger.class);
	private final MissedRunCatchUp catchUp =
			new MissedRunCatchUp(mock(ApplicationContext.class), mock(Environment.class), ledger);

	private static final ZoneId TORONTO = ZoneId.of("America/Toronto");
	/** 2026-10-06 13:00 Toronto (17:00 UTC): the 08:00 briefing slot was 5h ago. */
	private static final Instant NOW = Instant.parse("2026-10-06T17:00:00Z");

	private static MissedRunCatchUp.Job job(String key, String cron) {
		return new MissedRunCatchUp.Job(key, null, null, CronExpression.parse(cron), TORONTO);
	}

	@Test
	void aDailyJobWhoseSlotPassedWhileDownIsCaughtUp() {
		MissedRunCatchUp.Job learner = job("com.argus.learning.TradeLearner.nightly", "0 30 3 * * *");
		when(ledger.lastRun(learner.key())).thenReturn(Optional.of(Instant.parse("2026-10-05T07:30:33Z")));

		assertEquals(Optional.of(Instant.parse("2026-10-06T07:30:00Z")), catchUp.missedSlot(learner, NOW));
	}

	@Test
	void aJobThatAlreadyRanSinceItsLastSlotIsLeftAlone() {
		MissedRunCatchUp.Job learner = job("com.argus.learning.TradeLearner.nightly", "0 30 3 * * *");
		when(ledger.lastRun(learner.key())).thenReturn(Optional.of(Instant.parse("2026-10-06T07:30:40Z")));

		assertTrue(catchUp.missedSlot(learner, NOW).isEmpty());
	}

	@Test
	void hourlyJobsAreNeverCaughtUpTheirNextSlotIsSoonEnough() {
		MissedRunCatchUp.Job hourly = job("com.argus.calendar.CalendarAlertService.scheduledScan", "0 0 * * * *");

		assertTrue(catchUp.missedSlot(hourly, NOW).isEmpty());
		verify(ledger, never()).lastRun(hourly.key());
	}

	@Test
	void aJobSeenForTheFirstTimeIsStampedNotRun() {
		MissedRunCatchUp.Job fresh = job("com.argus.x.New.job", "0 0 2 * * *");
		when(ledger.lastRun(fresh.key())).thenReturn(Optional.empty());

		assertTrue(catchUp.missedSlot(fresh, NOW).isEmpty());
		verify(ledger).recordRun(fresh.key());
	}

	@Test
	void aMorningBriefingIsOnlyCaughtUpWhileItIsStillMorningEnough() {
		MissedRunCatchUp.Job briefing = job("com.argus.briefing.BriefingService.scheduledBriefing", "0 0 8 * * *");
		when(ledger.lastRun(briefing.key())).thenReturn(Optional.of(Instant.parse("2026-10-05T12:00:21Z")));

		assertTrue(catchUp.missedSlot(briefing, NOW).isPresent(), "5h late: still worth sending");
		assertTrue(catchUp.missedSlot(briefing, Instant.parse("2026-10-07T03:00:00Z")).isEmpty(),
				"11 PM: no 'morning' briefing at midnight — tomorrow's slot is next");
	}

	@Test
	void aWeeklyJobMissedDaysAgoStillCatchesUpWithinThreeDays() {
		MissedRunCatchUp.Job weekly = job("com.argus.intelligence.MacroKeywordLearningService.scheduledReview", "0 0 4 * * SUN");
		when(ledger.lastRun(weekly.key())).thenReturn(Optional.of(Instant.parse("2026-09-28T08:00:00Z")));

		assertTrue(catchUp.missedSlot(weekly, NOW).isPresent(), "Sunday's slot, 2 days late, is caught up");
		assertTrue(catchUp.missedSlot(weekly, Instant.parse("2026-10-09T17:00:00Z")).isEmpty(), "5 days late: wait for Sunday");
	}

	/** The ledger key the scheduler records must equal the key the catch-up looks up — both come from Spring's naming. */
	@Test
	void theSchedulerSeesTheSameJobNameTheCatchUpUses() throws Exception {
		Method m = Sample.class.getDeclaredMethod("nightly");
		Runnable scheduled = new CronTask(new ScheduledMethodRunnable(new Sample(), m), "0 0 1 * * *").getRunnable();

		assertEquals(Sample.class.getName() + ".nightly", scheduled.toString());
	}

	static class Sample {
		void nightly() {
		}
	}
}
