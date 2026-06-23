package com.argus.portfolio;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;

/**
 * Seam for automatic corporate-action detection (Story 3.3). Intentionally NOT implemented here —
 * automatic detection (e.g. scheduled polling of Finnhub {@code /stock/split}) is a scheduled-agent
 * concern deferred to later work. A future detector feeds {@link CorporateActionService#record}
 * exactly like the manual entry endpoint does.
 */
public interface CorporateActionDetector {

	List<Detected> detectFor(Collection<String> tickers);

	/** A detected corporate action awaiting recording/application. */
	record Detected(String ticker, CorporateActionType type, BigDecimal ratio, String newTicker,
			LocalDate exDate) {
	}
}
