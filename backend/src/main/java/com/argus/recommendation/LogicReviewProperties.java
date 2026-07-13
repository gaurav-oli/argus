package com.argus.recommendation;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Analyst Logic Review configuration ({@code argus.logic-review.*}). The review is automated: the LLM
 * proposes per-agent weight-multiplier factors and a deterministic backtest over closed trades decides
 * whether to adopt them — so the safety lives here, not in the model.
 *
 * @param enabled     master switch for running the review at all
 * @param autoApply   when true, adopt (write the refined multipliers live) if the backtest approves;
 *                    when false, still run + backtest + log, but never modify live weights (shadow mode)
 * @param minSample   closed trades required before the review will consider any change
 * @param factorMin   lower clamp on an LLM-proposed multiplier factor
 * @param factorMax   upper clamp on an LLM-proposed multiplier factor
 * @param brierMargin required Brier improvement (proposed must be at least this much lower) to adopt
 */
@ConfigurationProperties("argus.logic-review")
public record LogicReviewProperties(
		@DefaultValue("true") boolean enabled,
		@DefaultValue("true") boolean autoApply,
		@DefaultValue("20") int minSample,
		@DefaultValue("0.5") double factorMin,
		@DefaultValue("1.5") double factorMax,
		@DefaultValue("0.01") double brierMargin) {
}
