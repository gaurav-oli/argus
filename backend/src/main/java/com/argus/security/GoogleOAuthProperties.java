package com.argus.security;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Google Sign-In configuration ({@code argus.google-oauth.*}, set via the {@code ARGUS_GOOGLE_*}
 * env vars). Empty client credentials are a deliberate default — with none set, Google login is
 * simply unreachable (the start endpoint 503s) rather than failing confusingly mid-flow.
 *
 * @param clientId     OAuth 2.0 Client ID from Google Cloud Console
 * @param clientSecret OAuth 2.0 Client Secret from Google Cloud Console — never logged, never
 *                     returned to the client
 * @param redirectUri  must exactly match an "Authorized redirect URI" registered on that client —
 *                     {@code https://<tailnet-host>/api/login/oauth2/code/google} on the Mini
 */
@ConfigurationProperties("argus.google-oauth")
public record GoogleOAuthProperties(
		@DefaultValue("") String clientId,
		@DefaultValue("") String clientSecret,
		@DefaultValue("http://localhost:3000/api/login/oauth2/code/google") String redirectUri) {

	public boolean configured() {
		return !clientId.isBlank() && !clientSecret.isBlank();
	}
}
