package com.argus.notification;

import java.time.Duration;

/**
 * Dedup window for notifications (Story 8.4, FR-20). Collapses repeats of the same ticker+direction
 * inside a rolling window so only the highest-confidence one fires. Abstracted so the
 * {@link NotificationService} pipeline is unit-testable without Redis.
 */
public interface NotificationDedupStore {

	/**
	 * Decide whether this alert should fire under dedup. Returns {@code true} when no equal-or-higher
	 * confidence has fired for {@code ticker}+{@code direction} inside the window — and records this
	 * confidence as the new window maximum. Returns {@code false} (a duplicate) otherwise, bumping the
	 * collapsed-count. Alerts without a ticker always fire (dedup doesn't apply).
	 */
	boolean accept(String ticker, String direction, double confidence, Duration window);

	/** How many duplicates have been collapsed for this key in the current window — for logging. */
	long dedupeCount(String ticker, String direction);
}
