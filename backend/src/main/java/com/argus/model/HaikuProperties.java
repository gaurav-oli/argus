package com.argus.model;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Claude Haiku escalation config ({@code argus.haiku.*}, Story 7.3). The Anthropic chat model is
 * built manually + key-gated in {@link HaikuConfig} (Spring AI's Anthropic autoconfig is disabled
 * via {@code spring.ai.model.chat=ollama}, since the selector is mutually exclusive with Ollama's).
 *
 * @param apiKey      Anthropic API key; blank = escalation unavailable (Argus runs fully local)
 * @param model       Haiku model id (default {@code claude-haiku-4-5-20251001})
 * @param maxTokens   max output tokens (Anthropic requires it)
 * @param temperature sampling temperature
 */
@ConfigurationProperties("argus.haiku")
public record HaikuProperties(
		@DefaultValue("") String apiKey,
		@DefaultValue("claude-haiku-4-5-20251001") String model,
		@DefaultValue("1024") int maxTokens,
		@DefaultValue("0.7") double temperature) {
}
