package com.argus.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

/**
 * STOMP-over-WebSocket: clients connect at {@code /ws}, subscribe under {@code /topic},
 * and send to {@code /app}. In-memory simple broker (single-user; no external broker).
 *
 * <p>Allowed origins share {@link WebProperties} with {@link CorsConfig} ({@code
 * argus.web.allowed-origins} / {@code ARGUS_WEB_ALLOWED_ORIGINS}) rather than the previous
 * wide-open {@code "*"} — a naive single hardcoded origin would break the Mini's single-origin
 * Tailscale deploy (the tailnet host must be allowed), which is exactly why this now reuses the
 * same env-configurable list CORS already validates against, instead of introducing a second one.
 */
@Configuration
@EnableWebSocketMessageBroker
@EnableConfigurationProperties(WebProperties.class)
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

	private final WebProperties webProperties;

	public WebSocketConfig(WebProperties webProperties) {
		this.webProperties = webProperties;
	}

	@Override
	public void configureMessageBroker(MessageBrokerRegistry registry) {
		registry.enableSimpleBroker("/topic");
		registry.setApplicationDestinationPrefixes("/app");
	}

	@Override
	public void registerStompEndpoints(StompEndpointRegistry registry) {
		registry.addEndpoint("/ws").setAllowedOriginPatterns(webProperties.allowedOrigins().toArray(String[]::new));
	}
}
