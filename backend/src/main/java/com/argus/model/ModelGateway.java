package com.argus.model;

/**
 * The single entry point for all LLM access in Argus. Callers (agents, Ask AI,
 * personas) depend on this interface and never touch Spring AI, Ollama, or
 * Anthropic directly (architecture model boundary).
 */
public interface ModelGateway {

	/**
	 * Generate a completion for the given prompt using the active-profile model
	 * (mock under {@code dev}, Gemma via Ollama under {@code prod}). Big-model
	 * access is serialized; on primary-model failure a Haiku fallback is invoked.
	 */
	String generate(String prompt);
}
