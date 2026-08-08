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
 * <p>{@code smallModel} is different — it IS effective. There's only one Spring AI {@link
 * org.springframework.ai.chat.model.ChatModel} bean (bound to {@code bigModel} via the native keys
 * above), so {@link DefaultModelGateway} gets a distinct small-tier model by passing {@code
 * smallModel} as a per-call {@code OllamaChatOptions} override on SMALL-tier generations only —
 * BIG-tier calls are untouched and keep using the bean's configured default model.
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
 * @param smallModel  effective (see note above) — the always-resident, small model used for
 *                    high-frequency SMALL-tier calls (Agent 1/2/3 sentiment/relevance), so they stop
 *                    contending with and reloading the big model (Epic 4 follow-up, architecture
 *                    Decision 1). Small enough to sit alongside the big model within the Mini's 28GB
 *                    without triggering the swap-thrashing a second large model caused.
 * @param devResponse canned response returned by the dev-profile mock model
 */
@ConfigurationProperties("argus.model")
@Validated
public record ModelGatewayProperties(
		@Min(1) @DefaultValue("1") int concurrency,
		@DefaultValue("150s") Duration callTimeoutSeconds,
		@DefaultValue("10m") Duration keepAlive,
		@DefaultValue("gemma3:27b") String bigModel,
		@DefaultValue("llama3.2:3b") String smallModel,
		@DefaultValue("[dev-mock] Argus Model Gateway is alive.") String devResponse) {
}
