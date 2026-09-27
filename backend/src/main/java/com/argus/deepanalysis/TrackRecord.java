package com.argus.deepanalysis;

/**
 * How Agent 11's own past verdicts of one kind actually did — the feedback that lets it discount itself. {@code hitRate} is the
 * share that beat (WORTH_BUYING) or lagged (NOT_WORTH_BUYING) the S&amp;P 500 at {@code horizonDays}.
 */
public record TrackRecord(int n, double hitRate, double meanExcessPct, int horizonDays) {
}
