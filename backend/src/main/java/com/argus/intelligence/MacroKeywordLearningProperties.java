package com.argus.intelligence;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Macro keyword learning configuration ({@code argus.macro-keyword-learning.*}). The review is
 * automated: the model proposes new keywords from real misses and a deterministic gate (corroboration
 * + a known-ambiguous-word stoplist) decides whether to adopt them — so the safety lives here, not in
 * the model. Same shape as {@link com.argus.recommendation.LogicReviewProperties}.
 *
 * @param enabled          master switch for running the review at all
 * @param autoApply        when true, adopt (write learned keywords live) if the gate approves; when
 *                         false, still run + log, but never modify the live keyword list (shadow mode)
 * @param minMisses        unreviewed misses required before the review will consider any change
 * @param minCorroboration distinct clustered misses a proposed keyword must cover before it's adopted
 * @param cron             schedule for the weekly review
 */
@ConfigurationProperties("argus.macro-keyword-learning")
public record MacroKeywordLearningProperties(
		@DefaultValue("true") boolean enabled,
		@DefaultValue("true") boolean autoApply,
		@DefaultValue("5") int minMisses,
		@DefaultValue("2") int minCorroboration,
		@DefaultValue("0 0 4 * * SUN") String cron) {
}
