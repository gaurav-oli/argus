package com.argus.portfolio;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Computes a position's weighted-average ACB across its lots (Story 3.2, FR-1b / A-16) — Canadian
 * non-registered convention, never FIFO/lot-specific. Pure + deterministic (no LLM): trade-currency
 * cost is the sum of lot costs; CAD ACB is the sum of {@code lotCost × lotPurchaseFx}. If any
 * contributing lot lacks a (cost, FX) pair the CAD ACB can't be computed exactly, so it is left
 * null and the position is flagged {@code fxEstimated}.
 */
@Component
public class AcbCalculator {

	/** Aggregated weighted-average ACB caches for a position. */
	public record Acb(BigDecimal shares, BigDecimal costBasis, String currency, BigDecimal cadAcb,
			boolean fxEstimated) {
	}

	public Acb compute(List<PositionLot> lots) {
		BigDecimal shares = BigDecimal.ZERO;
		BigDecimal costBasis = BigDecimal.ZERO;
		boolean anyCost = false;
		BigDecimal cadTotal = BigDecimal.ZERO;
		boolean cadComputable = true;
		boolean estimated = false;
		String currency = null;

		for (PositionLot lot : lots) {
			if (lot.getShares() != null) {
				shares = shares.add(lot.getShares());
			}
			if (currency == null) {
				currency = lot.getTradeCurrency();
			}
			if (lot.getTotalCost() != null) {
				costBasis = costBasis.add(lot.getTotalCost());
				anyCost = true;
			}
			if (lot.isFxEstimated()) {
				estimated = true;
			}
			// CAD leg needs both the lot cost and its purchase FX; otherwise it's not exact.
			if (lot.getTotalCost() != null && lot.getFxToCad() != null) {
				cadTotal = cadTotal.add(lot.getTotalCost().multiply(lot.getFxToCad()));
			} else {
				cadComputable = false;
			}
		}

		BigDecimal cadAcb = cadComputable && anyCost ? cadTotal.setScale(4, RoundingMode.HALF_UP) : null;
		if (cadAcb == null) {
			estimated = true; // can't pin CAD ACB without every lot's FX → surface as estimated
		}
		return new Acb(
				shares.setScale(6, RoundingMode.HALF_UP),
				anyCost ? costBasis.setScale(4, RoundingMode.HALF_UP) : null,
				currency,
				cadAcb,
				estimated);
	}
}
