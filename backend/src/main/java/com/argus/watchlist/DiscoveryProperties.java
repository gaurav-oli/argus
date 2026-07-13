package com.argus.watchlist;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Auto-discovery configuration ({@code argus.discovery.*}). The discovery agent ranks the tickers most
 * mentioned in recently-ingested social chatter that you don't already hold or watch, and promotes the
 * top few into the watchlist as DISCOVERED entries (auto-expiring). Capped so it can't flood the
 * universe and blow the ingestion rate limits.
 *
 * @param enabled        master switch for the discovery agent
 * @param maxDiscovered  cap on how many DISCOVERED entries exist at once
 * @param minMentions    a candidate needs at least this many social mentions in the window to qualify
 * @param lookbackDays   how far back to count mentions
 * @param ttlDays        how long a DISCOVERED entry lives before expiring (refreshed while still trending)
 */
@ConfigurationProperties("argus.discovery")
public record DiscoveryProperties(
		@DefaultValue("true") boolean enabled,
		@DefaultValue("10") int maxDiscovered,
		@DefaultValue("10") int minMentions,
		@DefaultValue("14") int lookbackDays,
		@DefaultValue("3") int ttlDays) {
}
