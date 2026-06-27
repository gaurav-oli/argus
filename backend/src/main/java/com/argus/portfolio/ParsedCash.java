package com.argus.portfolio;

import java.math.BigDecimal;

/** A cash balance extracted from a statement (Story 3.1) — folded into the portfolio total on import. */
public record ParsedCash(String account, String currency, BigDecimal amount) {
}
