package com.argus.calendar;

/**
 * Lead-time alert urgency for calendar reminders (Story 5.2, FR-22). {@link #GREEN} = normal lead
 * (surfaced in the morning briefing); {@link #YELLOW} = the event is within ~24h (more immediate).
 * The platform-wide 4-tier urgency system (FR-18) arrives in Epic 8.
 */
public enum AlertUrgency {
	GREEN,
	YELLOW
}
