package com.argus.model;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Claude escalation that goes through the Claude Code CLI (the owner's subscription) first and the API key only when
 * the CLI isn't configured, is paused after a usage limit, or a call fails — so Argus's Claude path never just stops.
 */
public class CliFirstHaikuFallback implements HaikuFallback {

	private static final Logger log = LoggerFactory.getLogger(CliFirstHaikuFallback.class);

	private final ClaudeCliClient cli;
	private final HaikuFallback api;
	private final String model;

	public CliFirstHaikuFallback(ClaudeCliClient cli, HaikuFallback api, String model) {
		this.cli = cli;
		this.api = api;
		this.model = model;
	}

	@Override
	public String generate(String prompt) {
		try {
			var r = cli.generate(model, null, prompt);
			if (r.isPresent() && !r.get().text().isBlank()) {
				log.info("event=claude_cli model={} inputTokens={} outputTokens={}", model, r.get().inputTokens(),
						r.get().outputTokens());
				return r.get().text();
			}
		}
		catch (ModelGatewayException ex) {
			log.warn("Claude CLI call failed ({}) — falling back to the API key", ex.getMessage());
		}
		return api.generate(prompt);
	}
}
