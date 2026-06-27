package com.argus.ops;

import java.time.Instant;

/**
 * Freshness of one data source for the Ops dashboard (Story 9.7). {@code stale} is true when the
 * source hasn't updated within its {@code thresholdMinutes} (or has never updated).
 *
 * @param source          stable client key (e.g. {@code "news"})
 * @param label           display name
 * @param lastUpdateAt    most-recent update, or null if the source has never produced data
 * @param ageMinutes      minutes since the last update, or null if never
 * @param stale           true when older than the threshold (or never)
 * @param thresholdMinutes the staleness threshold for this source
 */
public record SourceFreshness(String source, String label, Instant lastUpdateAt, Long ageMinutes,
		boolean stale, long thresholdMinutes) {
}
