package com.argus.grading;

/**
 * A step in the strategy rebuild's nightly loop that must run before the report cards and gates (simulating the
 * shadow book, writing post-mortems). Steps run in {@link org.springframework.core.annotation.Order} order.
 */
public interface NightlyStep {

	String name();

	void run();
}
