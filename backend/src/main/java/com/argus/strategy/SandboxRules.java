package com.argus.strategy;

import java.math.BigDecimal;

/**
 * S-B7 — when a sandboxed strategy moves between states. Pure.
 *
 * <p>A shadow call is a <em>hit</em> when the strategy's side beat SPY over the call's horizon (direction-adjusted
 * return minus SPY's). The baseline is a coin flip against the market: 50% hits and zero mean excess.
 * <ul>
 * <li>{@value #MIN_RESOLVED}+ resolved calls with at least {@value #PROMOTE_MIN_HIT_PCT}% hits and a positive mean
 * excess → {@code PROMOTED} (live);</li>
 * <li>{@value #MIN_RESOLVED}+ resolved and under 50% hits or a mean excess at or below zero → {@code KILLED};</li>
 * <li>still between the two at {@value #MAX_RESOLVED} resolved → {@code KILLED} (never cleared the bar);</li>
 * <li>{@value #CANDIDATE_AT}+ resolved and above the promotion bar so far → {@code CANDIDATE}; otherwise
 * {@code SHADOW}.</li>
 * </ul>
 * PROMOTED and KILLED are final.
 */
public final class SandboxRules {

	public enum State { SHADOW, CANDIDATE, PROMOTED, KILLED }

	static final int MIN_RESOLVED = 30;
	static final int CANDIDATE_AT = 15;
	static final int MAX_RESOLVED = 60;
	static final int PROMOTE_MIN_HIT_PCT = 55;

	private SandboxRules() {
	}

	public record Decision(State state, String reason) {
	}

	public static Decision next(State current, int resolved, int hits, BigDecimal meanExcessPct) {
		if (current == State.PROMOTED || current == State.KILLED) {
			return new Decision(current, null);
		}
		int hitPct = resolved == 0 ? 0 : Math.round(100f * hits / resolved);
		boolean positive = meanExcessPct != null && meanExcessPct.signum() > 0;
		String record = "%d of %d shadow calls beat SPY (%d%%), mean excess %s%%".formatted(hits, resolved, hitPct,
				meanExcessPct == null ? "—" : meanExcessPct.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString());
		boolean aboveBar = hitPct >= PROMOTE_MIN_HIT_PCT && positive;
		if (resolved >= MIN_RESOLVED) {
			if (aboveBar) {
				return new Decision(State.PROMOTED, record + " — beat the baseline; promoted to live Agent 5 scoring.");
			}
			if (hitPct < 50 || !positive) {
				return new Decision(State.KILLED, record + " — no better than a coin flip against the market; killed.");
			}
			if (resolved >= MAX_RESOLVED) {
				return new Decision(State.KILLED, record + " — never cleared the %d%% bar in %d calls; killed."
						.formatted(PROMOTE_MIN_HIT_PCT, MAX_RESOLVED));
			}
			return new Decision(State.SHADOW, record + " — above a coin flip but under the %d%% bar; still in shadow."
					.formatted(PROMOTE_MIN_HIT_PCT));
		}
		if (resolved >= CANDIDATE_AT && aboveBar) {
			return new Decision(State.CANDIDATE, record + " — on track; needs %d resolved to be promoted.".formatted(MIN_RESOLVED));
		}
		return new Decision(State.SHADOW, resolved == 0 ? "In shadow — no shadow calls resolved yet."
				: record + " — collecting; %d resolved needed.".formatted(MIN_RESOLVED));
	}

	/** Direction-adjusted return minus SPY's, in percent. {@code direction} is +1 (bullish) or -1 (bearish). */
	public static double excessPct(int direction, double entry, double exit, double spyEntry, double spyExit) {
		double stock = (exit - entry) / entry;
		double spy = (spyExit - spyEntry) / spyEntry;
		return 100 * direction * (stock - spy);
	}
}
