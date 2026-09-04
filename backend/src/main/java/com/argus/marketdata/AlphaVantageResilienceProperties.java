package com.argus.marketdata;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Alpha Vantage REST resilience configuration ({@code argus.alpha-vantage.resilience.*}). Defaults
 * suit the free tier's hard cap of 25 requests/day (no published per-minute limit) — a 24-hour
 * refresh window, not Finnhub's per-minute one.
 *
 * @param limitForPeriod       max Alpha Vantage calls permitted per refresh window
 * @param refreshPeriodSeconds length of the rate-limit window
 * @param acquireTimeoutSeconds how long a call waits for a permit before being dropped
 * @param maxAttempts          total attempts per call (1 try + retries) on transient failures
 * @param initialBackoffMs     first retry backoff, doubled each subsequent retry
 * @param backoffMultiplier    exponential backoff multiplier
 */
@ConfigurationProperties("argus.alpha-vantage.resilience")
public record AlphaVantageResilienceProperties(
		@DefaultValue("25") int limitForPeriod,
		@DefaultValue("86400") int refreshPeriodSeconds,
		@DefaultValue("5") int acquireTimeoutSeconds,
		@DefaultValue("3") int maxAttempts,
		@DefaultValue("500") long initialBackoffMs,
		@DefaultValue("2.0") double backoffMultiplier) {
}
