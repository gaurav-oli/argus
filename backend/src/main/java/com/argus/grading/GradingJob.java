package com.argus.grading;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The strategy rebuild's nightly loop: grade every prediction, rebuild the report cards, then let each gated phase
 * evaluate its pass test or switch-off. Runs after the evening candle ingestion, so the day's closes exist; missed
 * runs are caught up on boot by {@code MissedRunCatchUp} like any other cron job.
 */
@Component
public class GradingJob {

	private static final Logger log = LoggerFactory.getLogger(GradingJob.class);

	private final SignalGrader grader;
	private final ReportCardService cards;
	private final List<GateEvaluator> evaluators;
	private final List<NightlyStep> steps;
	private final boolean enabled;
	private final org.springframework.jdbc.core.JdbcTemplate jdbc;
	private final boolean catchUpOnBoot;
	/** Lets the boot-time candle top-ups land before grading reads them. */
	private static final java.time.Duration CATCH_UP_DELAY = java.time.Duration.ofMinutes(5);

	public GradingJob(SignalGrader grader, ReportCardService cards, List<GateEvaluator> evaluators, List<NightlyStep> steps,
			@Value("${argus.grading.enabled:true}") boolean enabled, org.springframework.jdbc.core.JdbcTemplate jdbc,
			@Value("${argus.boot-catch-up.enabled:true}") boolean catchUpOnBoot) {
		this.jdbc = jdbc;
		this.catchUpOnBoot = catchUpOnBoot;
		this.steps = steps;
		this.grader = grader;
		this.cards = cards;
		this.evaluators = evaluators;
		this.enabled = enabled;
	}

	@Scheduled(cron = "${argus.grading.cron:0 30 19 * * *}", zone = "America/New_York")
	public void nightly() {
		if (!enabled) {
			return;
		}
		runAll();
	}

	/**
	 * Boot catch-up: a new install (or a host that was down over 19:30) would otherwise wait for the next evening. If
	 * nothing has been graded in the last 20 hours, run one pass a few minutes after startup.
	 */
	@org.springframework.context.event.EventListener(org.springframework.boot.context.event.ApplicationReadyEvent.class)
	public void catchUpOnStartup() {
		if (!enabled || !catchUpOnBoot) {
			return;
		}
		Thread.startVirtualThread(() -> {
			try {
				Thread.sleep(CATCH_UP_DELAY);
				java.sql.Timestamp last = jdbc.queryForObject("select max(created_at) from signal_grade", java.sql.Timestamp.class);
				if (last == null || last.toInstant().isBefore(java.time.Instant.now().minus(java.time.Duration.ofHours(20)))) {
					log.info("Grading startup catch-up: last graded {}", last == null ? "never" : last.toInstant());
					runAll();
				}
			}
			catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
			}
			catch (RuntimeException ex) {
				log.warn("Grading startup catch-up failed: {}", ex.getMessage());
			}
		});
	}

	/** One full pass; each step isolated so one failure can't skip the rest. */
	public void runAll() {
		try {
			grader.run();
		}
		catch (RuntimeException ex) {
			log.warn("Grading failed: {}", ex.toString(), ex);
		}
		for (NightlyStep step : steps) {
			try {
				step.run();
			}
			catch (RuntimeException ex) {
				log.warn("Nightly step {} failed: {}", step.name(), ex.toString(), ex);
			}
		}
		try {
			cards.recompute();
		}
		catch (RuntimeException ex) {
			log.warn("Report-card recompute failed: {}", ex.toString(), ex);
		}
		for (GateEvaluator e : evaluators) {
			try {
				e.evaluate();
			}
			catch (RuntimeException ex) {
				log.warn("Gate {} evaluation failed: {}", e.gate(), ex.toString(), ex);
			}
		}
	}
}
