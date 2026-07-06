package com.argus.recommendation;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Adaptive-tuning configuration ({@code argus.adaptive-tuning.*}, Phase B). Defaults reflect the
 * chosen "on by default, wider/more-reactive" posture, with cold-start safety kept intact:
 * {@code minSample} plus {@code shrinkK} mean the learned multipliers barely move off 1.0 until a
 * real sample has accumulated, so the wide clamps can't overreact to a few early trades.
 *
 * @param enabled          master switch — false makes every multiplier 1.0 (identical to pre-Phase-B)
 * @param minSample        below this many contributing/binned trades, no adjustment is applied
 * @param shrinkK          Bayesian shrink constant: multiplier moves toward its raw value as n/(n+K)
 * @param weightGain       sensitivity of the weight multiplier to (hitRate − 0.5)
 * @param weightMin        lower clamp on an agent's weight multiplier
 * @param weightMax        upper clamp on an agent's weight multiplier
 * @param confidenceMin    lower clamp on the calibrated directional probability multiplier effect
 * @param confidenceMax    upper clamp (kept ≤ ~1.2 so calibration humbles faster than it emboldens)
 * @param recomputeOnBoot  also recompute once at startup (default off) — handy for validating the loop
 *                         without waiting for the nightly job
 */
@ConfigurationProperties("argus.adaptive-tuning")
public record AdaptiveTuningProperties(
		@DefaultValue("true") boolean enabled,
		@DefaultValue("10") int minSample,
		@DefaultValue("20") double shrinkK,
		@DefaultValue("1.0") double weightGain,
		@DefaultValue("0.25") double weightMin,
		@DefaultValue("2.0") double weightMax,
		@DefaultValue("0.5") double confidenceMin,
		@DefaultValue("1.2") double confidenceMax,
		@DefaultValue("false") boolean recomputeOnBoot) {
}
