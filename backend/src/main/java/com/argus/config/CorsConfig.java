package com.argus.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * CORS for the REST API. Allowed origins come from {@code argus.web.allowed-origins}
 * ({@link WebProperties}) — the local Next dev server by default, the tailnet host on the Mini.
 * {@code allowCredentials} is on so the session cookie (Story 2.1) is sent/accepted; a specific
 * origin list (never {@code *}) is required when credentials are allowed.
 *
 * <p>Behind {@code tailscale serve} the backend sees same-origin browser POSTs as cross-origin
 * (the Origin is the tailnet host, the request lands on 127.0.0.1), so the deployed origin MUST
 * be listed here or Spring rejects those POSTs with 403.
 */
@Configuration
@EnableConfigurationProperties(WebProperties.class)
public class CorsConfig implements WebMvcConfigurer {

	private final WebProperties webProperties;

	public CorsConfig(WebProperties webProperties) {
		this.webProperties = webProperties;
	}

	@Override
	public void addCorsMappings(CorsRegistry registry) {
		registry.addMapping("/api/**")
				.allowedOrigins(webProperties.allowedOrigins().toArray(String[]::new))
				.allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
				.allowCredentials(true);
	}
}
