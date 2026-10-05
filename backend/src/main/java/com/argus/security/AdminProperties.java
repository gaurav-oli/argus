package com.argus.security;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * The one admin email ({@code ARGUS_ADMIN_EMAIL}) — deliberately its own top-level property rather
 * than nested under Google OAuth settings: it's an app-wide designation (whoever can see usage
 * stats), not specifically a Google Sign-In config value, even though Google login is currently the
 * only thing that reads it (on first account creation — see GoogleAuthController).
 */
@ConfigurationProperties("argus.admin")
public record AdminProperties(@DefaultValue("") String email) {
}
