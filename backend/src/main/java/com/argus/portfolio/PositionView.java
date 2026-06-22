package com.argus.portfolio;

import java.math.BigDecimal;
import java.time.LocalDate;

/** A persisted portfolio holding as returned to the client (Story 3.1). */
public record PositionView(
		long id,
		String ticker,
		String companyName,
		BigDecimal shares,
		BigDecimal costBasis,
		String costBasisCurrency,
		LocalDate acquisitionDate,
		boolean needsReview,
		String source) {

	static PositionView of(Position p) {
		return new PositionView(p.getId(), p.getTicker(), p.getCompanyName(), p.getShares(),
				p.getCostBasis(), p.getCostBasisCurrency(), p.getAcquisitionDate(), p.isNeedsReview(),
				p.getSource());
	}
}
