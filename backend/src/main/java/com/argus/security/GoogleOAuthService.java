package com.argus.security;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.jose4j.jwk.HttpsJwks;
import org.jose4j.jwt.JwtClaims;
import org.jose4j.jwt.MalformedClaimException;
import org.jose4j.jwt.consumer.InvalidJwtException;
import org.jose4j.jwt.consumer.JwtConsumer;
import org.jose4j.jwt.consumer.JwtConsumerBuilder;
import org.jose4j.keys.resolvers.HttpsJwksVerificationKeyResolver;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Hand-rolled Google OAuth2 Authorization Code flow — not Spring Security's OAuth2 client, which
 * assumes it owns the whole login/session model; this app already has its own Redis-backed session
 * ({@link SessionStore}) that everything else trusts, so Google is wired in as just another way to
 * mint one of those sessions. {@code jose4j} (already a dependency, used by Web Push) does the one
 * part that must never be hand-rolled: verifying the ID token's signature against Google's live
 * public keys.
 */
@Service
public class GoogleOAuthService {

	private static final String AUTH_ENDPOINT = "https://accounts.google.com/o/oauth2/v2/auth";
	private static final String TOKEN_ENDPOINT = "https://oauth2.googleapis.com/token";
	private static final String JWKS_URI = "https://www.googleapis.com/oauth2/v3/certs";
	// Google's ID tokens carry either form depending on how they were issued; jose4j
	// setExpectedIssuers checks the token's issuer against this whole list, not just the first.
	private static final String[] ISSUERS = {"https://accounts.google.com", "accounts.google.com"};

	private final GoogleOAuthProperties props;
	private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
	private final ObjectMapper json = JsonMapper.builder().build();
	// HttpsJwks caches Google's public keys itself and refetches on a cache miss (e.g. after Google
	// rotates its signing keys) — exactly the resilience a long-lived verifier needs, with no manual
	// cache management on this end.
	private final HttpsJwksVerificationKeyResolver keyResolver = new HttpsJwksVerificationKeyResolver(new HttpsJwks(JWKS_URI));

	public GoogleOAuthService(GoogleOAuthProperties props) {
		this.props = props;
	}

	/** Where to send the browser to start Google's consent screen. {@code state} is an opaque,
	 * server-generated CSRF token the caller must have the browser carry via its own cookie and
	 * verify matches on the callback — the only thing proving the callback is a reply to THIS login. */
	public String authorizationUrl(String state) {
		String query = "client_id=" + enc(props.clientId())
				+ "&redirect_uri=" + enc(props.redirectUri())
				+ "&response_type=code"
				+ "&scope=" + enc("openid email profile")
				+ "&state=" + enc(state)
				+ "&prompt=select_account";
		return AUTH_ENDPOINT + "?" + query;
	}

	/** Exchange the authorization code for Google's signed ID token (verified separately below). */
	public String exchangeCodeForIdToken(String code) {
		String body = "code=" + enc(code)
				+ "&client_id=" + enc(props.clientId())
				+ "&client_secret=" + enc(props.clientSecret())
				+ "&redirect_uri=" + enc(props.redirectUri())
				+ "&grant_type=authorization_code";
		try {
			HttpRequest req = HttpRequest.newBuilder(URI.create(TOKEN_ENDPOINT))
					.timeout(Duration.ofSeconds(10))
					.header("Content-Type", "application/x-www-form-urlencoded")
					.POST(HttpRequest.BodyPublishers.ofString(body))
					.build();
			HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
			if (res.statusCode() != 200) {
				throw new GoogleAuthException("Google token exchange failed: HTTP " + res.statusCode());
			}
			JsonNode node = json.readTree(res.body());
			String idToken = node.path("id_token").asString(null);
			if (idToken == null) {
				throw new GoogleAuthException("Google's token response had no id_token");
			}
			return idToken;
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new GoogleAuthException("Google token exchange interrupted", ex);
		}
		catch (IOException ex) {
			throw new GoogleAuthException("Google token exchange failed: " + ex.getMessage(), ex);
		}
	}

	/**
	 * Verify the ID token's signature against Google's live public keys, plus issuer/audience/
	 * expiry, then return the identity claims. This is the one place Argus's whole multi-user
	 * security boundary actually sits — every field below is trustworthy only because this check ran.
	 */
	public GoogleIdentity verify(String idToken) {
		return verify(idToken, keyResolver);
	}

	/** Package-visible so tests can exercise the real issuer/audience/expiry/signature checks
	 * against a locally-generated key instead of Google's live JWKS endpoint. */
	GoogleIdentity verify(String idToken, org.jose4j.keys.resolvers.VerificationKeyResolver resolver) {
		JwtConsumer consumer = new JwtConsumerBuilder()
				.setRequireExpirationTime()
				.setAllowedClockSkewInSeconds(30)
				.setExpectedIssuers(true, ISSUERS)
				.setExpectedAudience(props.clientId())
				.setVerificationKeyResolver(resolver)
				.build();
		try {
			JwtClaims claims = consumer.processToClaims(idToken);
			boolean emailVerified = "true".equalsIgnoreCase(claims.getClaimValueAsString("email_verified"));
			return new GoogleIdentity(
					claims.getSubject(),
					claims.getClaimValueAsString("email"),
					emailVerified,
					claims.getClaimValueAsString("name"),
					claims.getClaimValueAsString("picture"));
		}
		catch (InvalidJwtException | MalformedClaimException ex) {
			throw new GoogleAuthException("Google ID token verification failed: " + ex.getMessage(), ex);
		}
	}

	private static String enc(String v) {
		return URLEncoder.encode(v, StandardCharsets.UTF_8);
	}
}
