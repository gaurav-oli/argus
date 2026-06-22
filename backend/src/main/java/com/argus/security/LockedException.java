package com.argus.security;

/**
 * Thrown when authentication is blocked by the failed-attempt lockout (FR-38 / Story 2.6).
 * A timed lockout carries the remaining seconds (→ HTTP 429 + Retry-After); a full lock has
 * {@code full == true} (→ HTTP 423, clearable only from another authenticated device).
 */
public class LockedException extends RuntimeException {

	private final boolean full;
	private final long retryAfterSeconds;

	private LockedException(boolean full, long retryAfterSeconds, String message) {
		super(message);
		this.full = full;
		this.retryAfterSeconds = retryAfterSeconds;
	}

	public static LockedException timed(long retryAfterSeconds) {
		return new LockedException(false, Math.max(retryAfterSeconds, 1),
				"Too many attempts — try again in %d seconds".formatted(Math.max(retryAfterSeconds, 1)));
	}

	public static LockedException full() {
		return new LockedException(true, 0, "Locked — unlock from another signed-in device");
	}

	public boolean isFull() {
		return full;
	}

	public long getRetryAfterSeconds() {
		return retryAfterSeconds;
	}
}
