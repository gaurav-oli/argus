package com.argus.notification;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Alert-discipline configuration ({@code argus.notification.*}, Epic 8).
 *
 * @param minConfidence       fatigue-gate floor (0–1): non-critical alerts fire only at/above this (Story 8.3)
 * @param minPortfolioImpact  fatigue-gate floor (0–1 fraction of the portfolio) for non-critical alerts (Story 8.3)
 * @param dedupWindowSeconds  dedup window — same ticker+direction inside it collapses to the highest (Story 8.4)
 */
@ConfigurationProperties("argus.notification")
public record NotificationProperties(
		@DefaultValue("0.60") double minConfidence,
		@DefaultValue("0.02") double minPortfolioImpact,
		@DefaultValue("1800") long dedupWindowSeconds) {
}
