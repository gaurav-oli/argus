package com.argus.notification;

/**
 * Alert urgency tiers (Story 8.2, FR-18). The tier decides routing in {@link NotificationService}:
 *
 * <ul>
 *   <li>{@code CRITICAL} — immediate push, marked require-ack, and bypasses the fatigue gate.</li>
 *   <li>{@code IMPORTANT} — immediate push (the ≤5-minute SLA is met by sending now on a single-user host).</li>
 *   <li>{@code NORMAL} — no push now; surfaced in the next Morning Briefing.</li>
 *   <li>{@code INFO} — no push now; surfaced in the weekly digest.</li>
 * </ul>
 */
public enum UrgencyTier {
	CRITICAL,
	IMPORTANT,
	NORMAL,
	INFO
}
