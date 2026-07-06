package com.argus.recommendation;

import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Agent 5 performance endpoints for the Operations dashboards (Epic 9), session-gated under
 * {@code /api/recommendations}. Separate from {@link RecommendationController} so the analytics
 * dependencies stay decoupled from the card/decision flow.
 */
@RestController
@RequestMapping("/api/recommendations")
public class PerformanceController {

	private final PerformanceService performance;
	private final PaperInvestorService investor;
	private final AdaptiveTuningService tuning;

	public PerformanceController(PerformanceService performance, PaperInvestorService investor,
			AdaptiveTuningService tuning) {
		this.performance = performance;
		this.investor = investor;
		this.tuning = tuning;
	}

	/** Story 9.2 — win rate over All/30d/last-10, issued, taken vs declined, graduation state. */
	@GetMapping("/accuracy")
	public PerformanceService.AccuracyView accuracy() {
		return performance.accuracy();
	}

	/** Story 9.3 — per-agent contribution % from logged signal weights. */
	@GetMapping("/attribution")
	public PerformanceService.AttributionView attribution() {
		return performance.attribution();
	}

	/** Story 9.4 — resolved recommendations binned by stated probability vs actual hit rate. */
	@GetMapping("/calibration")
	public PerformanceService.CalibrationView calibration() {
		return performance.calibration();
	}

	/** The Investor persona's autonomous paper-trading scoreboard: the $ book, its return, win rate. */
	@GetMapping("/paper-trades")
	public PaperInvestorService.Scoreboard paperTrades() {
		return investor.scoreboard();
	}

	/**
	 * Ops: force the Phase B adaptive-tuning recompute now (it otherwise runs nightly), and return the
	 * resulting per-agent reliability so the effect is immediately visible. Session-gated like all
	 * {@code /api/*} endpoints.
	 */
	@PostMapping("/tuning/recompute")
	public Map<String, AdaptiveTuningService.ReliabilityView> recomputeTuning() {
		tuning.recompute();
		return tuning.reliabilityByAgent();
	}
}
