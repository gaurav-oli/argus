package com.argus.model;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Configuration for the Model Gateway ({@code argus.model.*}).
 *
 * @param concurrency max concurrent big-model generations (serialized at 1 per Decision 1)
 * @param keepAlive   Ollama keep-alive for the on-demand big model (prod)
 * @param bigModel    the Ollama model tag for the big model (provisional pending Story 1.3)
 * @param devResponse canned response returned by the dev-profile mock model
 */
@ConfigurationProperties("argus.model")
public record ModelGatewayProperties(
		@DefaultValue("1") int concurrency,
		@DefaultValue("10m") Duration keepAlive,
		@DefaultValue("gemma3:27b") String bigModel,
		@DefaultValue("[dev-mock] Argus Model Gateway is alive.") String devResponse) {
}
