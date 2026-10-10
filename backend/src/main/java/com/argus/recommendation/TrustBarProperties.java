package com.argus.recommendation;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * The paper-validation trust bar (S-B2): what the current Agent 5 system must show on paper before
 * Argus stops calling itself "paper validation only". Every check is judged on the <b>current system</b>
 * (paper trades opened since conviction scoring went live), never the old system's coin flips.
 * Clearing it changes the message only: Argus never places orders either way.
 *
 * <p>Override any value with its env var (see {@code application.yml} / {@code .env.example}).
 *
 * @param requiredState   graduation state Agent 5 must be in ({@link GraduationState}); ACTIVE by default,
 *                        so a SHADOW, PROBATION or FROZEN agent never clears the bar
 * @param minClosedTrades closed current-system paper trades needed before the numbers count
 * @param minWinRatePct   current-system paper win rate needed (percent)
 * @param maxBrier        highest acceptable Brier score (0 = perfect, 0.25 = coin flip)
 */
@ConfigurationProperties("argus.trust-bar")
public record TrustBarProperties(
		@DefaultValue("ACTIVE") String requiredState,
		@DefaultValue("50") int minClosedTrades,
		@DefaultValue("55") int minWinRatePct,
		@DefaultValue("0.22") double maxBrier) {
}
