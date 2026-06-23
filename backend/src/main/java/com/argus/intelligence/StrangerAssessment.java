package com.argus.intelligence;

/**
 * The computed pump-and-dump assessment for a stranger ticker (Story 4.4) — the auditable inputs
 * ({@code coverageCount}, {@code distinctSources}, {@code averageSourceScore}) alongside the derived
 * {@code riskScore} (0–100). Kept separate from the entity so the scoring is a pure, testable
 * function of its inputs.
 */
public record StrangerAssessment(
		int coverageCount,
		int distinctSources,
		double averageSourceScore,
		int riskScore) {
}
