package com.argus.portfolio;

import java.math.BigDecimal;
import java.time.LocalDate;

/** A persisted portfolio holding as returned to the client (Stories 3.1 + 3.2). */
public record PositionView(
		long id,
		String ticker,
		String companyName,
		BigDecimal shares,
		BigDecimal costBasis,
		String costBasisCurrency,
		BigDecimal cadAcb,
		boolean fxEstimated,
		LocalDate acquisitionDate,
		boolean needsReview,
		String source) {

	static PositionView of(Position p) {
		return new PositionView(p.getId(), p.getTicker(), p.getCompanyName(), p.getShares(),
				p.getCostBasis(), p.getCostBasisCurrency(), p.getCadAcb(), p.isFxEstimated(),
				p.getAcquisitionDate(), p.isNeedsReview(), p.getSource());
	}
}
