package com.argus.model;

/**
 * The single entry point for all LLM access in Argus. Callers (agents, Ask AI,
 * personas) depend on this interface and never touch Spring AI, Ollama, or
 * Anthropic directly (architecture model boundary).
 */
public interface ModelGateway {

	/**
	 * Generate a completion on the requested {@link ModelTier} using the active-profile model
	 * (mock under {@code dev}, Ollama under {@code prod}). {@link ModelTier#BIG} access is
	 * serialized and routes to a Haiku fallback on failure; {@link ModelTier#SMALL} runs
	 * unserialized with no fallback and propagates failure to the caller.
	 */
	String generate(String prompt, ModelTier tier);

	/** Convenience for {@link ModelTier#BIG} (Ask AI, Personas, deep-analysis agents). */
	default String generate(String prompt) {
		return generate(prompt, ModelTier.BIG);
	}
}
