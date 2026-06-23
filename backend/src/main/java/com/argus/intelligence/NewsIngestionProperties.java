package com.argus.intelligence;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Agent 1 news-ingestion configuration ({@code argus.news.*}, Story 4.1).
 *
 * <p>The scheduler ticks every {@code pollMs} but only runs a cycle once the cadence for the current
 * session has elapsed — {@code regularCadenceMs} during regular US market hours (≤5 min), the slower
 * {@code offHoursCadenceMs} otherwise (≤15 min) — satisfying FR-8's "≤5-min cycle (≤15m off-hours)".
 *
 * @param pollMs            scheduler tick interval (how often the cadence gate is evaluated)
 * @param regularCadenceMs  minimum gap between cycles during regular market hours
 * @param offHoursCadenceMs minimum gap between cycles off-hours
 * @param lookbackMinutes   how far back per-ticker sources query, and the freshness cutoff
 * @param gdelt             GDELT broad-feed settings
 * @param rss               RSS feed settings
 */
@ConfigurationProperties("argus.news")
public record NewsIngestionProperties(
		@DefaultValue("60000") long pollMs,
		@DefaultValue("300000") long regularCadenceMs,
		@DefaultValue("900000") long offHoursCadenceMs,
		@DefaultValue("60") long lookbackMinutes,
		@DefaultValue Gdelt gdelt,
		@DefaultValue Rss rss) {

	/**
	 * @param enabled    whether the GDELT source is active
	 * @param query      GDELT Doc API query string
	 * @param maxRecords max articles per fetch
	 */
	public record Gdelt(
			@DefaultValue("true") boolean enabled,
			@DefaultValue("(stock OR earnings OR \"stock market\")") String query,
			@DefaultValue("75") int maxRecords) {
	}

	/**
	 * @param feeds RSS feed URLs to poll (empty disables the RSS source)
	 */
	public record Rss(@DefaultValue List<String> feeds) {
	}
}
