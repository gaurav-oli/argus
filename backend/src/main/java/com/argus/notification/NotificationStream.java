package com.argus.notification;

/**
 * The Redis stream onto which the platform emits user-facing notification events (auto-block alerts,
 * pending recommendations, event reminders, …). The notification service (Web Push, alert-fatigue
 * gate, urgency tiers — Epic 8) consumes this; producers across the app publish to it via
 * {@code AgentEventPublisher}. Defined here so the key has a single owner ahead of Epic 8.
 */
public final class NotificationStream {

	/** Stream key for outbound notification events. */
	public static final String KEY = "argus:stream:notifications";

	private NotificationStream() {
	}
}
