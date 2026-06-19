package com.argus.model;

/**
 * Fallback to Claude Haiku when the primary (local) model fails or the budget
 * governor diverts traffic (FR-45). Wired into the gateway now; the real
 * Anthropic call is implemented in a later story.
 */
public interface HaikuFallback {

	String generate(String prompt);
}
