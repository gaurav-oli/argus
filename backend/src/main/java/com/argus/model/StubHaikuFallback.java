package com.argus.model;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Placeholder Haiku fallback (Story 1.4 skeleton). The path exists and is invoked
 * by the gateway on primary-model failure, but the real Claude Haiku call is not
 * wired yet.
 *
 * <p>TODO (FR-45 / cost governor story): add the Spring AI Anthropic starter, an
 * API key, and the budget gate, then return a real Haiku completion here.
 */
@Component
public class StubHaikuFallback implements HaikuFallback {

	private static final Logger log = LoggerFactory.getLogger(StubHaikuFallback.class);

	static final String PLACEHOLDER = "[haiku-fallback-stub] not implemented yet";

	@Override
	public String generate(String prompt) {
		log.warn("Haiku fallback invoked but not yet implemented (stub) — returning placeholder");
		return PLACEHOLDER;
	}
}
