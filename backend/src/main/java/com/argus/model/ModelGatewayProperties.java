package com.argus.model;

import jakarta.validation.constraints.Min;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Configuration for the Model Gateway ({@code argus.model.*}).
 *
 * <p>NOTE: {@code keepAlive} and {@code bigModel} are currently informational only — the values
 * that actually reach Ollama are bound by Spring AI's native keys
 * ({@code spring.ai.ollama.chat.options.keep-alive} / {@code .model} in {@code application-prod.yml}).
 * They are kept here for a future move to gateway-owned model config; tune the {@code spring.ai.*}
 * keys (or {@code ARGUS_MODEL_KEEP_ALIVE} / {@code ARGUS_BIG_MODEL}) to change runtime behavior.
 *
 * @param concurrency       max concurrent big-model generations (serialized at 1 per Decision 1).
 *                          {@link DefaultModelGateway} also guards this at construction time — a
 *                          value below 1 would create a permanently-unacquirable semaphore, quietly
 *                          bricking every BIG-tier call — so a bad value fails loudly at startup
 *                          rather than surfacing as an unexplained 503 on first real use.
 * @param callTimeoutSeconds max time to wait for a big-model permit AND, separately, to wait for
 *                          the model call itself before giving up and falling through to the Haiku
 *                          fallback (Epic 1 hardening backlog — Story 1.4). The observed worst case
 *                          on the Mini is ~115s (a broken `gemma4` build burning its full token
 *                          budget on junk tokens before returning blank, see
 *                          docs/mac-mini-validation.md §3/§6) — the default sits comfortably above
 *                          that so legitimate slow answers aren't cut off, while still bounding the
 *                          previously-unbounded worst case (a genuine network/model hang) to a
 *                          finite wait instead of starving every queued caller forever.
 * @param keepAlive   informational (see note) — effective key is spring.ai.ollama.chat.options.keep-alive
 * @param bigModel    informational (see note) — effective key is spring.ai.ollama.chat.options.model
 * @param devResponse canned response returned by the dev-profile mock model
 */
@ConfigurationProperties("argus.model")
@Validated
public record ModelGatewayProperties(
		@Min(1) @DefaultValue("1") int concurrency,
		@DefaultValue("150s") Duration callTimeoutSeconds,
		@DefaultValue("10m") Duration keepAlive,
		@DefaultValue("gemma3:27b") String bigModel,
		@DefaultValue("[dev-mock] Argus Model Gateway is alive.") String devResponse) {
}
