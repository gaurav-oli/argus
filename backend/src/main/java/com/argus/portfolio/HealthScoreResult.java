package com.argus.portfolio;

import java.time.Instant;
import java.util.List;

/** The current Portfolio Health Score + its explained deductions (Story 3.8). */
public record HealthScoreResult(int score, List<HealthDeduction> deductions, Instant computedAt) {
}
