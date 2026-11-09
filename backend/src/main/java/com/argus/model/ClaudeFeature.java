package com.argus.model;

/**
 * Features that ask Claude through the Claude Code CLI (the owner's subscription) first and the local big model when
 * the CLI is unavailable — work where reading or writing quality matters and volume is low. Each can be switched off
 * with {@code argus.claude-first.features}. Never the paid API: these used to be local-only, so they must never add
 * API spend. Prediction work (headline sentiment) is deliberately not here until the model experiment says Claude
 * scores better.
 */
public enum ClaudeFeature {
	/** Filings Reader digests (10-K / 8-K) — long documents. */
	FILINGS("filings"),
	/** Ask-AI chat answers (the "deeper" button already escalates). */
	CHAT("chat"),
	/** On-demand research planning and step answers. */
	RESEARCH("research"),
	/** Brokerage statement extraction. */
	STATEMENTS("statements"),
	/** Briefing, market pulse, plain-English explanations and persona notes. */
	WRITING("writing"),
	/** Agent 13 rule proposals (the backtest still decides). */
	LEARNER("learner");

	private final String key;

	ClaudeFeature(String key) {
		this.key = key;
	}

	public String key() {
		return key;
	}
}
