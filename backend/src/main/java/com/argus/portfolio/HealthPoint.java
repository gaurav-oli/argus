package com.argus.portfolio;

import java.time.LocalDate;

/** One point in the Health Score trend (Story 3.9, FR-7). */
public record HealthPoint(LocalDate date, int score) {
}
