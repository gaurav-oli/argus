package com.argus.recommendation;

import java.util.List;
import java.util.Locale;

/**
 * Pure evaluation of the paper-validation trust bar (S-B2) — no I/O, so every edge is unit-testable.
 * {@link TrustBarService} gathers the real numbers; this decides, check by check, whether the bar clears.
 */
public final class TrustBar {

	private TrustBar() {
	}

	/** The numbers the bar is judged on — all from the current system's paper book. */
	public record Inputs(String graduationState, long closedTrades, Integer winRatePct, Double brier) {
	}

	/** One line of the checklist: what is required, what the agent actually shows, and whether it passes. */
	public record Check(String key, String label, String required, String actual, boolean pass) {
	}

	/**
	 * The bar's verdict. {@code headline} is the exact message the UI shows; it always ends by saying
	 * Argus never places orders, because clearing the bar is a statement about the track record only.
	 */
	public record View(boolean cleared, int passed, int total, List<Check> checks, String headline,
			TrustBarProperties thresholds) {
	}

	static final String NOT_CLEARED = "Paper validation only — trust bar not cleared.";
	static final String CLEARED = "Trust bar cleared on paper — still advisory: Argus never places orders.";

	public static View evaluate(TrustBarProperties bar, Inputs in) {
		String state = in.graduationState() == null ? "UNKNOWN" : in.graduationState();
		List<Check> checks = List.of(
				new Check("state", "Agent 5 graduation", bar.requiredState(), state,
						bar.requiredState().equalsIgnoreCase(state)),
				new Check("sample", "Closed paper trades", "≥ " + bar.minClosedTrades(), String.valueOf(in.closedTrades()),
						in.closedTrades() >= bar.minClosedTrades()),
				new Check("winRate", "Paper win rate", "≥ " + bar.minWinRatePct() + "%",
						in.winRatePct() == null ? "—" : in.winRatePct() + "%",
						in.winRatePct() != null && in.winRatePct() >= bar.minWinRatePct()),
				new Check("brier", "Calibration (Brier)", "≤ " + format(bar.maxBrier()),
						in.brier() == null ? "—" : format(in.brier()),
						in.brier() != null && in.brier() <= bar.maxBrier()));
		int passed = (int) checks.stream().filter(Check::pass).count();
		boolean cleared = passed == checks.size();
		return new View(cleared, passed, checks.size(), checks, cleared ? CLEARED : NOT_CLEARED, bar);
	}

	private static String format(double brier) {
		return String.format(Locale.ROOT, "%.3f", brier);
	}
}
