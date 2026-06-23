package com.argus.portfolio;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/** A corporate action as returned to the client (Story 3.3). */
public record CorporateActionView(
		long id,
		String ticker,
		Long positionId,
		String type,
		BigDecimal ratio,
		String newTicker,
		LocalDate exDate,
		String status,
		String note,
		String source,
		Instant createdAt,
		Instant appliedAt) {

	static CorporateActionView of(CorporateAction a) {
		return new CorporateActionView(a.getId(), a.getTicker(), a.getPositionId(), a.getType(),
				a.getRatio(), a.getNewTicker(), a.getExDate(), a.getStatus(), a.getNote(), a.getSource(),
				a.getCreatedAt(), a.getAppliedAt());
	}
}
