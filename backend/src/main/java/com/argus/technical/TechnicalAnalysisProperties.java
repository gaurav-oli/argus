package com.argus.technical;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Agent 10 configuration ({@code argus.technical.*}).
 *
 * @param backfillDays              how far back to fetch on a ticker's first-ever candle ingest, so
 *                                  indicators (SMA50, 60-day drawdown) are usable immediately rather
 *                                  than waiting weeks of daily polling
 * @param causeTriggerDrawdownPct  a drawdown-from-high threshold (negative %) that must be crossed
 *                                  before {@code agent-11-cause} spends an LLM call classifying why —
 *                                  bounds cost to real dips, not every ticker every day
 * @param causeConfidenceFloor     minimum classification confidence before a MACRO_EXTERNAL +
 *                                  temporary read is trusted enough to emit a bullish signal
 */
@ConfigurationProperties("argus.technical")
public record TechnicalAnalysisProperties(
		@DefaultValue("200") int backfillDays,
		@DefaultValue("-8.0") double causeTriggerDrawdownPct,
		@DefaultValue("0.6") double causeConfidenceFloor) {
}
