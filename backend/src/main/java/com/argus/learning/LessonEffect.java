package com.argus.learning;

import java.util.List;

/**
 * What the active lessons say about a specific decision. Every consumer — the recommender's policy, the
 * paper Investor, Agent 11 — asks {@link Lessons#evaluate} and applies the parts that concern it.
 *
 * @param scoreDelta      conviction-score points to add (negative = penalty), already clamped
 * @param blockReason     non-null when a BLOCK rule matches: do not act, and here is why
 * @param holdCapDays     maximum holding period the lessons allow, or null
 * @param sizeMultiplier  position-size multiplier (1.0 = unchanged)
 * @param applied         each matching rule, so the decision can show which lessons shaped it
 */
public record LessonEffect(int scoreDelta, String blockReason, Integer holdCapDays, double sizeMultiplier, List<Applied> applied) {

	/** One matching rule, in a form ready to display. */
	public record Applied(Long ruleId, String kind, String description, String stats, double effect) {
	}

	public static LessonEffect none() {
		return new LessonEffect(0, null, null, 1.0, List.of());
	}

	public boolean isNone() {
		return scoreDelta == 0 && blockReason == null && holdCapDays == null && sizeMultiplier == 1.0 && applied.isEmpty();
	}
}
