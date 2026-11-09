package com.argus.grading;

/** A gated phase's nightly evaluation: measure, then promote, hold, fail or revert via {@link StrategyGateService}. */
public interface GateEvaluator {

	String gate();

	void evaluate();
}
