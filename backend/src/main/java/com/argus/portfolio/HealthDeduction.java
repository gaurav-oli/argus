package com.argus.portfolio;

/**
 * One point deduction in the Portfolio Health Score (Story 3.8, FR-6/FR-7). Each carries a specific,
 * actionable reason + suggested fix so the score is fully explainable (credit-score model).
 */
public record HealthDeduction(String code, String label, int points, String reason, String suggestion) {
}
