package com.argus.portfolio;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * One holding extracted from a statement PDF (Story 3.1, FR-1). Any field the parser could not
 * confidently extract is left {@code null} and named in {@code issues}, with {@code needsReview}
 * set — the row is kept and flagged for manual entry, never dropped. Serialized into the staged
 * import batch and returned in the upload preview.
 */
public record ParsedHolding(
		String ticker,
		String companyName,
		BigDecimal shares,
		BigDecimal costBasis,
		String costBasisCurrency,
		LocalDate acquisitionDate,
		boolean needsReview,
		List<String> issues) {

	public ParsedHolding {
		issues = issues == null ? List.of() : List.copyOf(issues);
	}
}
