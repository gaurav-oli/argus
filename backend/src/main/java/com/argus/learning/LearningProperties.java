package com.argus.learning;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Agent 13 (Trade Learner) configuration ({@code argus.learning.*}).
 *
 * @param enabled           master switch for the nightly run and for applying rules (off = every lesson is ignored)
 * @param explainWithModel  whether the local model writes the "why" hypotheses and the narrative; the rules themselves
 *                          never depend on it
 */
@ConfigurationProperties("argus.learning")
public record LearningProperties(
		@DefaultValue("true") boolean enabled,
		@DefaultValue("true") boolean explainWithModel) {
}
