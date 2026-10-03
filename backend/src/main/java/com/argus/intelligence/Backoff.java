package com.argus.intelligence;

import java.time.Duration;

/**
 * Exponential backoff after consecutive failures, capped at a ceiling — for a source that's already
 * rate-limiting or timing out, so it stops getting hit on every scheduled cycle regardless. Pure: no
 * I/O, no clock of its own — the caller supplies the failure count and gets back how long to wait.
 */
final class Backoff {

	private Backoff() {
	}

	/**
	 * {@code initial} after the first failure, doubling each consecutive one, never exceeding {@code max}.
	 * Zero for {@code consecutiveFailures <= 0} (nothing to back off from).
	 */
	static Duration duration(int consecutiveFailures, Duration initial, Duration max) {
		if (consecutiveFailures <= 0) {
			return Duration.ZERO;
		}
		// Cap the shift, not just the result — 2^30 would already overflow a long multiplied by any
		// realistic `initial`, and by shift 10 the value is already far past any sane `max` anyway.
		long shift = Math.min(consecutiveFailures - 1, 10);
		long ms = Math.min(max.toMillis(), initial.toMillis() * (1L << shift));
		return Duration.ofMillis(ms);
	}
}
