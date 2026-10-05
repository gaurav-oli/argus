package com.argus.config;

import com.argus.security.SessionStore;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

/**
 * STOMP-over-WebSocket: clients connect at {@code /ws}, subscribe under {@code /topic} for shared
 * broadcasts (agent status, market pulse) or {@code /user/queue} for personal pushes (Phase 2's live
 * portfolio value — see {@link SessionPrincipalHandshakeHandler}), and send to {@code /app}. In-memory
 * simple broker (personal-scale friend group; no external broker).
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
	private final SessionStore sessions;

	public WebSocketConfig(WebProperties webProperties, SessionStore sessions) {
		this.webProperties = webProperties;
		this.sessions = sessions;
	}

	@Override
	public void configureMessageBroker(MessageBrokerRegistry registry) {
		registry.enableSimpleBroker("/topic", "/queue");
		registry.setApplicationDestinationPrefixes("/app");
		registry.setUserDestinationPrefix("/user");
	}

	@Override
	public void registerStompEndpoints(StompEndpointRegistry registry) {
		registry.addEndpoint("/ws")
				.setAllowedOriginPatterns(webProperties.allowedOrigins().toArray(String[]::new))
				.setHandshakeHandler(new SessionPrincipalHandshakeHandler(sessions));
	}
}
