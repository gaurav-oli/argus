package com.argus.intelligence;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Breaking-news alert configuration ({@code argus.breaking.*}). Tunes what counts as "immediate,
 * market-moving" and how aggressively it may push, so the phone gets the big stuff without being spammed.
 *
 * @param enabled          master switch for breaking-news push alerts
 * @param impactThreshold  relevance × |sentiment| at/above which a holdings-material story alerts
 * @param sentimentMin     |sentiment| a breaking-topic (macro) story needs to alert
 * @param maxPerHour       cap on alerts pushed per rolling hour (anti-spam)
 * @param cooldownMinutes  suppress a repeat of the same headline within this many minutes
 * @param maxAgeHours      only alert on news published within this window — "immediate" means fresh,
 *                         so an old story re-listed by a feed never fires a push
 * @param llmConfirm       gate a candidate through a fast Haiku YES/NO ("would this move markets?") to
 *                         cut false positives; fail-open (sends if the model errors) and skipped when
 *                         the Cost Governor has paused paid calls
 */
@ConfigurationProperties("argus.breaking")
public record BreakingNewsProperties(
		@DefaultValue("true") boolean enabled,
		@DefaultValue("0.5") double impactThreshold,
		@DefaultValue("0.45") double sentimentMin,
		@DefaultValue("4") int maxPerHour,
		@DefaultValue("180") int cooldownMinutes,
		@DefaultValue("6") int maxAgeHours,
		@DefaultValue("true") boolean llmConfirm) {
}
