package com.argus.model;

/**
 * The {@link HaikuFallback} wired when no Anthropic API key is configured (default dev/laptop, or a
 * key-less prod). It throws so a model failure or an explicit escalation surfaces as a clean error
 * (mapped to 503) — never placeholder text returned as a success (resolves the 7.1/7.2 HIGH).
 */
public class UnavailableHaikuFallback implements HaikuFallback {

	@Override
	public String generate(String prompt) {
		throw new ModelGatewayException("Deeper analysis is unavailable — no Anthropic API key configured.");
	}
}
