package com.argus.calendar;

/**
 * Kinds of calendar event Agent 7 tracks (Story 5.1, FR-21). {@link #EARNINGS} and
 * {@link #EX_DIVIDEND} are per-holding; {@link #FED}/{@link #CPI}/{@link #JOBS}/{@link #GDP} are
 * macro (no ticker); {@link #LOCKUP} is per-holding (IPO lock-up expiry).
 */
public enum CalendarEventType {
	EARNINGS,
	EX_DIVIDEND,
	LOCKUP,
	FED,
	CPI,
	JOBS,
	GDP
}
