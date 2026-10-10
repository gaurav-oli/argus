package com.argus.recommendation;

import com.argus.security.CurrentUserService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Agent 5 performance endpoints for the Operations dashboards (Epic 9), session-gated under
 * {@code /api/recommendations}. Separate from {@link RecommendationController} so the analytics
 * dependencies stay decoupled from the card/decision flow. Tuning recompute is admin-only (S-A3).
 */
@RestController
@RequestMapping("/api/recommendations")
public class PerformanceController {

	private final PerformanceService performance;
	private final PaperInvestorService investor;
	private final AdaptiveTuningService tuning;
	private final CurrentUserService currentUser;
	private final TrustBarService trustBar;

	public PerformanceController(PerformanceService performance, PaperInvestorService investor,
			AdaptiveTuningService tuning, CurrentUserService currentUser, TrustBarService trustBar) {
		this.performance = performance;
		this.investor = investor;
		this.tuning = tuning;
		this.currentUser = currentUser;
		this.trustBar = trustBar;
	}

	/**
	 * S-B2 — the paper-validation trust bar: whether the current system's paper record clears the
	 * configured bar, check by check. Session-gated like the rest of the performance endpoints.
	 */
	@GetMapping("/trust-bar")
	public TrustBar.View trustBar() {
		return trustBar.current();
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
	/** The Investor's full trade journal: every paper trade, buy to sell. */
	@GetMapping("/paper-trades/ledger")
	public List<PaperInvestorService.LedgerRow> ledger() {
		return investor.ledger();
	}

	@GetMapping("/paper-trades")
	public PaperInvestorService.Scoreboard paperTrades() {
		return investor.scoreboard();
	}

	/** Regret analysis — how the user's Taken vs Declined calls actually played out (paper outcomes). */
	@GetMapping("/regret")
	public PerformanceService.RegretView regret() {
		return performance.regret();
	}

	/**
	 * Ops: force the Phase B adaptive-tuning recompute now (it otherwise runs nightly). Admin-only
	 * (S-A3).
	 */
	@PostMapping("/tuning/recompute")
	public Map<String, AdaptiveTuningService.ReliabilityView> recomputeTuning(HttpServletRequest request) {
		currentUser.requireAdmin(request);
		tuning.recompute();
		return tuning.reliabilityByAgent();
	}
}
