package com.argus.portfolio;

import java.math.BigDecimal;
import java.time.LocalDate;

/** One point in the portfolio value chart series (Story 3.6). */
public record ValuePoint(LocalDate date, BigDecimal totalValueCad) {
}
