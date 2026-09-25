package com.argus.learning;

import java.util.Set;

/**
 * The single interface through which the whole system consults what it has learned. A decision is
 * described by a set of feature tokens ({@link FeatureTokens}); the lessons return their effect on it.
 * Consumers never read rules directly, so the learner can change how rules are mined or applied without
 * touching them.
 */
public interface Lessons {

	/** The combined effect of every active rule whose predicates are all present in {@code tokens}. */
	LessonEffect evaluate(Set<String> tokens);

	/**
	 * A plain-text block of the measured lessons relevant to {@code tokens}, for an LLM prompt — empty when
	 * none apply. These are outcomes measured on real paper trades, not opinions.
	 */
	String promptSection(Set<String> tokens);

	/** A no-op instance (no lessons known) — for tests and for contexts where learning is off. */
	static Lessons none() {
		return new Lessons() {
			@Override
			public LessonEffect evaluate(Set<String> tokens) {
				return LessonEffect.none();
			}

			@Override
			public String promptSection(Set<String> tokens) {
				return "";
			}
		};
	}
}
