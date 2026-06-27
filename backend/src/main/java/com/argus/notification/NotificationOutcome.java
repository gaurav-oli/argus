package com.argus.notification;

/** What {@link NotificationService} did with a {@link Notification} — returned for logging/testing. */
public enum NotificationOutcome {
	/** Sent as a Web Push now. */
	PUSHED,
	/** Held for the next Morning Briefing (NORMAL tier). */
	DEFERRED_BRIEFING,
	/** Held for the weekly digest (INFO tier). */
	DEFERRED_DIGEST,
	/** Dropped by the fatigue gate — below confidence/impact thresholds (Story 8.3). */
	SUPPRESSED_GATE,
	/** Dropped as a duplicate within the dedup window (Story 8.4). */
	SUPPRESSED_DEDUP
}
