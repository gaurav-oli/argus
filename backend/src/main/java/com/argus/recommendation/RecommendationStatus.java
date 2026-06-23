package com.argus.recommendation;

/**
 * Lifecycle of a recommendation card (Stories 6.2/6.7). {@link #PENDING} when produced; the user can
 * {@link #WATCHED} or {@link #DISMISSED} it, or log a trade as {@link #TAKEN}/{@link #DECLINED}.
 */
public enum RecommendationStatus {
	PENDING,
	WATCHED,
	DISMISSED,
	TAKEN,
	DECLINED
}
