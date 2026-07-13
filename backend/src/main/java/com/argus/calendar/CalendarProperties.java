package com.argus.calendar;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Agent 7 calendar configuration ({@code argus.calendar.*}, Story 5.1).
 *
 * @param lookaheadDays how far ahead to ingest events
 * @param fedEnabled    whether the Fed RSS source is active
 * @param fedRssUrl     Fed press-release RSS feed for FOMC/macro events
 * @param ipoEnabled    whether the Finnhub upcoming-IPO source is active (needs the Finnhub key)
 * @param ipoMinValueUsd only surface IPOs whose deal size is at least this many USD when the size is
 *                       known (0 = no filter); IPOs that haven't priced yet (unknown size) are always
 *                       included so nothing upcoming is hidden
 */
@ConfigurationProperties("argus.calendar")
public record CalendarProperties(
		@DefaultValue("90") int lookaheadDays,
		@DefaultValue("true") boolean fedEnabled,
		@DefaultValue("https://www.federalreserve.gov/feeds/press_all.xml") String fedRssUrl,
		@DefaultValue("true") boolean ipoEnabled,
		@DefaultValue("50000000") long ipoMinValueUsd) {
}
