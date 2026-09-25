package com.argus.recommendation;

/**
 * The overall lean a bull-vs-bear researcher debate ({@link RecommendationDebateService}) reached.
 * Deliberately not a reuse of {@link SignalDirection}: {@code NEUTRAL} there means "this agent
 * abstained," which is the wrong meaning for "the debate concluded it's a genuine toss-up" — SPLIT is
 * a real conclusion, not an abstention.
 */
public enum DebateVerdict {
	BULL,
	BEAR,
	SPLIT
}
