package com.argus.model;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Configuration for the Model Gateway ({@code argus.model.*}).
 *
 * <p>NOTE: {@code keepAlive} and {@code bigModel} are currently informational only — the values
 * that actually reach Ollama are bound by Spring AI's native keys
 * ({@code spring.ai.ollama.chat.options.keep-alive} / {@code .model} in {@code application-prod.yml}).
 * They are kept here for a future move to gateway-owned model config; tune the {@code spring.ai.*}
 * keys (or {@code ARGUS_MODEL_KEEP_ALIVE} / {@code ARGUS_BIG_MODEL}) to change runtime behavior.
 *
 * @param concurrency max concurrent big-model generations (serialized at 1 per Decision 1)
 * @param keepAlive   informational (see note) — effective key is spring.ai.ollama.chat.options.keep-alive
 * @param bigModel    informational (see note) — effective key is spring.ai.ollama.chat.options.model
 * @param devResponse canned response returned by the dev-profile mock model
 */
@ConfigurationProperties("argus.model")
public record ModelGatewayProperties(
		@DefaultValue("1") int concurrency,
		@DefaultValue("10m") Duration keepAlive,
		@DefaultValue("gemma3:27b") String bigModel,
		@DefaultValue("[dev-mock] Argus Model Gateway is alive.") String devResponse) {
}
