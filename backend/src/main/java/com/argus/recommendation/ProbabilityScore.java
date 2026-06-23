package com.argus.recommendation;

import java.util.List;

/**
 * The auditable output of the {@link ProbabilityScoringEngine} (Story 6.1, GAP-6). Every number is
 * derived by an explicit rule from the input signals — none originates from an LLM — and the
 * {@code contributions} list makes each one traceable to its inputs.
 *
 * @param bullProbability probability the thesis is bullish, 0–1 (0.5 when there is no directional signal)
 * @param bearProbability {@code 1 - bullProbability}
 * @param confidence      0–1: how much to trust the probability, from agreement × coverage
 * @param bullScore       summed weight of bullish signals
 * @param bearScore       summed weight of bearish signals
 * @param contributions   per-signal breakdown (the audit trail)
 */
public record ProbabilityScore(
		double bullProbability,
		double bearProbability,
		double confidence,
		double bullScore,
		double bearScore,
		List<Contribution> contributions) {

	/** One signal's contribution to the score. {@code signedWeight} is {@code direction.sign() * weight}. */
	public record Contribution(String agent, SignalDirection direction, double weight, double signedWeight) {
	}
}
