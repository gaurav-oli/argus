package com.argus.technical;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Agent 10 configuration ({@code argus.technical.*}).
 *
 * @param backfillDays how far back (calendar days) to keep candles, so the chart study has a full year of
 *                     history for a 200-day average and the 52-week range; a ticker with fewer than 200
 *                     stored bars is refetched with a full year on the next ingest
 */
@ConfigurationProperties("argus.technical")
public record TechnicalAnalysisProperties(
		@DefaultValue("400") int backfillDays) {
}
