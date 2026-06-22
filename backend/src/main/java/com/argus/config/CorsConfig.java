package com.argus.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Allows the local Next.js dev server ({@code :3000}) to call the REST API ({@code :8080}).
 * {@code allowCredentials} is on so the session cookie (Story 2.1) is sent/accepted on this
 * cross-port dev origin; the Mini deploy is single-origin (Tailscale serve) so CORS doesn't
 * apply there. A specific origin (not {@code *}) is required when credentials are allowed.
 */
@Configuration
public class CorsConfig implements WebMvcConfigurer {

	@Override
	public void addCorsMappings(CorsRegistry registry) {
		registry.addMapping("/api/**")
				.allowedOrigins("http://localhost:3000")
				.allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
				.allowCredentials(true);
	}
}
