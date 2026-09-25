package com.argus.learning;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Agent 13 endpoints for the Intelligence page, session-gated under {@code /api/learning}. */
@RestController
@RequestMapping("/api/learning")
public class LearningController {

	private final LearnedRuleRepository rules;
	private final LearningReportRepository reports;
	private final TradeLearner learner;
	private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "trade-learner");
		t.setDaemon(true);
		return t;
	});
	private final AtomicBoolean running = new AtomicBoolean(false);

	public LearningController(LearnedRuleRepository rules, LearningReportRepository reports, TradeLearner learner) {
		this.rules = rules;
		this.reports = reports;
		this.learner = learner;
	}

	@GetMapping
	public LearningView view() {
		List<RuleView> active = rules.findByStatusOrderByActivatedAtDesc(LearnedRule.Status.ACTIVE).stream().map(RuleView::from).toList();
		List<RuleView> others = rules.findTop50ByOrderByCreatedAtDesc().stream()
				.filter(r -> r.getStatus() == LearnedRule.Status.REJECTED || r.getStatus() == LearnedRule.Status.RETIRED).limit(12).map(RuleView::from).toList();
		return new LearningView(reports.findFirstByOrderByCreatedAtDesc().map(ReportView::from).orElse(null), active, others, running.get());
	}

	/** "Learn now" — starts a pass in the background and returns at once. */
	@PostMapping("/run")
	public RunStatus run() {
		if (running.compareAndSet(false, true)) {
			executor.submit(() -> {
				try {
					learner.run();
				}
				finally {
					running.set(false);
				}
			});
		}
		return new RunStatus(true);
	}

	public record RunStatus(boolean started) {
	}

	public record LearningView(ReportView report, List<RuleView> activeRules, List<RuleView> otherRules, boolean running) {
	}

	public record ReportView(int tradesAnalyzed, int independentBets, BigDecimal baselineWin, BigDecimal baselineExcess, String losses, String wins,
			String narrative, String model, Instant at) {

		static ReportView from(LearningReport r) {
			return new ReportView(r.getTradesAnalyzed(), r.getClusters(), r.getBaselineWin(), r.getBaselineExcess(), r.getLossesSummary(),
					r.getWinsSummary(), r.getNarrative(), r.getModel(), r.getCreatedAt());
		}
	}

	public record RuleView(Long id, String kind, String status, String description, String explanation, BigDecimal effect, int bets, BigDecimal winRate,
			BigDecimal meanExcess, Integer holdoutBets, BigDecimal holdoutMeanExcess, String note, Instant activatedAt) {

		static RuleView from(LearnedRule r) {
			return new RuleView(r.getId(), r.getKind().name(), r.getStatus().name(), r.getDescription(), r.getExplanation(), r.getEffectValue(),
					r.getSupportClusters(), r.getWinRate(), r.getMeanExcess(), r.getHoldoutClusters(), r.getHoldoutMeanExcess(), r.getVerdictNote(),
					r.getActivatedAt());
		}
	}
}
