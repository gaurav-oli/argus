package com.argus.security;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Security configuration ({@code argus.security.*}).
 *
 * @param sessionTtl   idle lifetime of a Redis-backed session before it expires and re-auth is
 *                     required. Default 15m (FR-35). Story 2.3 makes this user-configurable — keep
 *                     this the single source so that change has one place to write to.
 * @param cookieSecure whether the session cookie carries the {@code Secure} flag. Default true
 *                     (required on the HTTPS Tailscale deploy). Set false only for plain-HTTP local
 *                     dev, where browsers refuse to store {@code Secure} cookies from an http origin.
 */
@ConfigurationProperties("argus.security")
public record SecurityProperties(
		@DefaultValue("15m") Duration sessionTtl,
		@DefaultValue("true") boolean cookieSecure) {
}
