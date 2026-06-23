package com.argus.calendar;

import java.time.LocalDate;

/**
 * A ticker's pre-earnings posture (Story 5.3, FR-23), consumed by Agent 5 (Epic 6):
 * <ul>
 *   <li>{@link Status#QUIET} — earnings within 2 trading days: Agent 5 surfaces an "earnings ahead"
 *       card instead of a recommendation.</li>
 *   <li>{@link Status#NOTE} — earnings within 5 calendar days (outside the quiet period): the date is
 *       noted in the recommendation's bear scenario.</li>
 *   <li>{@link Status#CLEAR} — no imminent earnings.</li>
 * </ul>
 *
 * @param status            the posture
 * @param nextEarnings      the next earnings date, or null if none upcoming
 * @param tradingDaysUntil  business days until earnings (-1 if none upcoming)
 */
public record QuietPeriodStatus(Status status, LocalDate nextEarnings, int tradingDaysUntil) {

	public enum Status {
		CLEAR,
		NOTE,
		QUIET
	}

	public static QuietPeriodStatus clear() {
		return new QuietPeriodStatus(Status.CLEAR, null, -1);
	}
}
