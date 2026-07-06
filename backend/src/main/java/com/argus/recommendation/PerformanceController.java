package com.argus.recommendation;

import org.springframework.web.bind.annotation.GetMapping;
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

	public PerformanceController(PerformanceService performance, PaperInvestorService investor) {
		this.performance = performance;
		this.investor = investor;
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
}
