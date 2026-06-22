package com.argus.security;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the custom session-auth layer (Decision 5 keeps full Spring Security deferred). Binds
 * {@link SecurityProperties} and registers {@link SessionAuthFilter} for {@code /api/*} only.
 * ({@link SessionStore} and {@link PinHasher} are component-scanned.)
 */
@Configuration
@EnableConfigurationProperties(SecurityProperties.class)
public class SecurityConfig {

	@Bean
	FilterRegistrationBean<SessionAuthFilter> sessionAuthFilter(SessionStore sessions) {
		FilterRegistrationBean<SessionAuthFilter> registration = new FilterRegistrationBean<>(
				new SessionAuthFilter(sessions));
		registration.addUrlPatterns("/api/*");
		registration.setOrder(0);
		return registration;
	}
}
