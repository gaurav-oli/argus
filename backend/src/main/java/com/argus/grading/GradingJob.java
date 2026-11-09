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
	private final boolean enabled;

	public GradingJob(SignalGrader grader, ReportCardService cards, List<GateEvaluator> evaluators,
			@Value("${argus.grading.enabled:true}") boolean enabled) {
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

	/** One full pass; each step isolated so one failure can't skip the rest. */
	public void runAll() {
		try {
			grader.run();
		}
		catch (RuntimeException ex) {
			log.warn("Grading failed: {}", ex.toString(), ex);
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
