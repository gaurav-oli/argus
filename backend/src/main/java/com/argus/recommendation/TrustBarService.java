package com.argus.recommendation;

import org.springframework.stereotype.Service;

/**
 * Gathers the current system's paper numbers and evaluates the trust bar (S-B2). Reuses the existing
 * graduation state machine and the accuracy / calibration figures the Agents page already shows, so
 * "trusted" has exactly one definition across the app.
 */
@Service
public class TrustBarService {

	private final TrustBarProperties bar;
	private final PerformanceService performance;
	private final GraduationService graduation;

	public TrustBarService(TrustBarProperties bar, PerformanceService performance, GraduationService graduation) {
		this.bar = bar;
		this.performance = performance;
		this.graduation = graduation;
	}

	public TrustBar.View current() {
		PerformanceService.EraStat current = performance.accuracy().currentSystem();
		Double brier = performance.calibration().brierScore();
		return TrustBar.evaluate(bar, new TrustBar.Inputs(graduation.currentState().name(),
				current == null ? 0 : current.closed(), current == null ? null : current.winRatePct(), brier));
	}
}
