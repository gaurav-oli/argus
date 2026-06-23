package com.argus.marketdata;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Finnhub REST resilience + fallback configuration ({@code argus.finnhub.resilience.*}, Story 4.5,
 * GAP-4). Defaults suit the Finnhub free tier (60 calls/minute).
 *
 * @param limitForPeriod      max Finnhub REST calls permitted per refresh window
 * @param refreshPeriodSeconds length of the rate-limit window
 * @param acquireTimeoutSeconds how long a call waits for a permit before being dropped
 * @param maxAttempts         total attempts per call (1 try + retries) on transient failures
 * @param initialBackoffMs    first retry backoff, doubled each subsequent retry
 * @param backoffMultiplier   exponential backoff multiplier
 * @param fallbackProvider    fallback data provider when Finnhub is unavailable: none|alpha_vantage|yahoo
 */
@ConfigurationProperties("argus.finnhub.resilience")
public record FinnhubResilienceProperties(
		@DefaultValue("60") int limitForPeriod,
		@DefaultValue("60") int refreshPeriodSeconds,
		@DefaultValue("5") int acquireTimeoutSeconds,
		@DefaultValue("3") int maxAttempts,
		@DefaultValue("500") long initialBackoffMs,
		@DefaultValue("2.0") double backoffMultiplier,
		@DefaultValue("none") String fallbackProvider) {
}
