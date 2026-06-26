package com.argus.recommendation;

import com.argus.recommendation.ProbabilityScore.Contribution;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * The auditable probability scoring engine (Story 6.1, GAP-6). It combines explicit, weighted agent
 * signals into a bull/bear probability and a confidence score with a transparent rule — <b>no number
 * comes from an LLM</b>, and every output is traceable to its input {@link Contribution}s.
 *
 * <ul>
 *   <li><b>Probability</b> = bullish weight ÷ (bullish + bearish weight), <b>shrunk toward 0.5 by a
 *       neutral prior</b> so thin evidence reads as near-50/50 rather than a false 100%. One weak
 *       signal nudges the odds; only broad, strong, consistent signals approach the extremes. With no
 *       directional signal the probability is exactly 0.5 (maximum uncertainty).</li>
 *   <li><b>Confidence</b> = agreement × (0.4 + 0.6 × coverage), where <i>agreement</i> is the directional
 *       consensus |bull − bear| ÷ (bull + bear) and <i>coverage</i> is how many of the 7 agents weighed
 *       in. Conflicting signals drive confidence toward 0; broad consensus drives it toward 1.</li>
 * </ul>
 */
@Component
public class ProbabilityScoringEngine {

	/** The full agent fleet — coverage is measured against this (FR-13's "7-agent" signal dots). */
	static final int EXPECTED_AGENTS = 7;

	/**
	 * Pseudo-weight of neutral prior evidence pinned at 0.5. Acts as Bayesian shrinkage: the bull
	 * probability only departs from 50/50 as the real directional weight grows past this. Tuned so a
	 * lone mid-strength signal lands ~60–67% (a nudge), while several strong consistent signals can
	 * still reach the 80s.
	 */
	static final double NEUTRAL_PRIOR = 1.0;

	public ProbabilityScore score(List<AgentSignal> signals) {
		double bullScore = 0;
		double bearScore = 0;
		int directionalCount = 0;
		List<Contribution> contributions = new ArrayList<>();
		for (AgentSignal s : signals) {
			double signed = s.direction().sign() * s.weight();
			contributions.add(new Contribution(s.agent(), s.direction(), s.weight(), signed));
			if (s.direction() == SignalDirection.BULLISH) {
				bullScore += s.weight();
				directionalCount++;
			} else if (s.direction() == SignalDirection.BEARISH) {
				bearScore += s.weight();
				directionalCount++;
			}
		}

		double directional = bullScore + bearScore;
		// Shrink toward 0.5 with a neutral prior so a single thin signal isn't reported as 100%.
		double bullProbability = (bullScore + NEUTRAL_PRIOR * 0.5) / (directional + NEUTRAL_PRIOR);
		double agreement = directional == 0 ? 0 : Math.abs(bullScore - bearScore) / directional;
		double coverage = Math.min(1.0, (double) directionalCount / EXPECTED_AGENTS);
		double confidence = clamp01(agreement * (0.4 + 0.6 * coverage));

		return new ProbabilityScore(bullProbability, 1.0 - bullProbability, confidence,
				bullScore, bearScore, List.copyOf(contributions));
	}

	private static double clamp01(double v) {
		return Math.max(0.0, Math.min(1.0, v));
	}
}
