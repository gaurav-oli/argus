package com.argus.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import org.jose4j.jwk.RsaJsonWebKey;
import org.jose4j.jwk.RsaJwkGenerator;
import org.jose4j.jws.AlgorithmIdentifiers;
import org.jose4j.jws.JsonWebSignature;
import org.jose4j.jwt.JwtClaims;
import org.jose4j.jwt.NumericDate;
import org.jose4j.keys.resolvers.VerificationKeyResolver;
import org.junit.jupiter.api.Test;

/**
 * Exercises the real jose4j verification path (signature, issuer, audience, expiry) against a
 * locally-generated RSA key instead of Google's live JWKS — the crypto logic is identical, only the
 * key source differs ({@link GoogleOAuthService#verify(String, VerificationKeyResolver)}).
 */
class GoogleOAuthServiceTest {

	private static final String CLIENT_ID = "test-client-id.apps.googleusercontent.com";

	private final GoogleOAuthProperties props = new GoogleOAuthProperties(
			CLIENT_ID, "test-secret", "https://example.ts.net/api/login/oauth2/code/google", "");
	private final GoogleOAuthService service = new GoogleOAuthService(props);

	private static RsaJsonWebKey newKey() throws Exception {
		RsaJsonWebKey key = RsaJwkGenerator.generateJwk(2048);
		key.setKeyId("test-key-1");
		return key;
	}

	private static String sign(RsaJsonWebKey key, JwtClaims claims) throws Exception {
		JsonWebSignature jws = new JsonWebSignature();
		jws.setPayload(claims.toJson());
		jws.setKey(key.getPrivateKey());
		jws.setKeyIdHeaderValue(key.getKeyId());
		jws.setAlgorithmHeaderValue(AlgorithmIdentifiers.RSA_USING_SHA256);
		return jws.getCompactSerialization();
	}

	private static JwtClaims validClaims() {
		JwtClaims claims = new JwtClaims();
		claims.setIssuer("https://accounts.google.com");
		claims.setAudience(CLIENT_ID);
		claims.setSubject("1234567890");
		claims.setExpirationTimeMinutesInTheFuture(5);
		claims.setIssuedAtToNow();
		claims.setClaim("email", "friend@gmail.com");
		claims.setClaim("email_verified", "true");
		claims.setClaim("name", "A Friend");
		claims.setClaim("picture", "https://example.com/pic.jpg");
		return claims;
	}

	private static VerificationKeyResolver trusting(RsaJsonWebKey key) {
		return (jws, nestingContext) -> key.getKey();
	}

	@Test
	void verifiesAGenuinelySignedTokenAndExtractsIdentity() throws Exception {
		RsaJsonWebKey key = newKey();
		String token = sign(key, validClaims());

		GoogleIdentity identity = service.verify(token, trusting(key));

		assertEquals("1234567890", identity.sub());
		assertEquals("friend@gmail.com", identity.email());
		assertTrue(identity.emailVerified());
		assertEquals("A Friend", identity.name());
		assertEquals("https://example.com/pic.jpg", identity.pictureUrl());
	}

	@Test
	void rejectsATokenSignedWithAKeyTheResolverDoesNotTrust() throws Exception {
		RsaJsonWebKey trustedKey = newKey();
		RsaJsonWebKey attackerKey = newKey();
		String token = sign(attackerKey, validClaims()); // signed by someone else entirely

		assertThrows(GoogleAuthException.class, () -> service.verify(token, trusting(trustedKey)));
	}

	@Test
	void rejectsAnExpiredToken() throws Exception {
		RsaJsonWebKey key = newKey();
		JwtClaims claims = validClaims();
		claims.setExpirationTime(NumericDate.fromSeconds(Instant.now().minusSeconds(60).getEpochSecond()));
		String token = sign(key, claims);

		assertThrows(GoogleAuthException.class, () -> service.verify(token, trusting(key)));
	}

	@Test
	void rejectsTheWrongAudience() throws Exception {
		RsaJsonWebKey key = newKey();
		JwtClaims claims = validClaims();
		claims.setAudience("someone-elses-client-id.apps.googleusercontent.com");
		String token = sign(key, claims);

		assertThrows(GoogleAuthException.class, () -> service.verify(token, trusting(key)));
	}

	@Test
	void rejectsAnUntrustedIssuer() throws Exception {
		RsaJsonWebKey key = newKey();
		JwtClaims claims = validClaims();
		claims.setIssuer("https://not-google.example.com");
		String token = sign(key, claims);

		assertThrows(GoogleAuthException.class, () -> service.verify(token, trusting(key)));
	}

	@Test
	void acceptsTheBareAccountsGoogleComIssuerFormToo() throws Exception {
		RsaJsonWebKey key = newKey();
		JwtClaims claims = validClaims();
		claims.setIssuer("accounts.google.com"); // the other valid form Google actually issues
		String token = sign(key, claims);

		assertEquals("1234567890", service.verify(token, trusting(key)).sub());
	}

	@Test
	void anUnverifiedEmailIsReportedNotRejectedHere() throws Exception {
		// GoogleOAuthService only verifies the token's authenticity; GoogleAuthController decides
		// what an unverified email means for sign-in.
		RsaJsonWebKey key = newKey();
		JwtClaims claims = validClaims();
		claims.setClaim("email_verified", "false");
		String token = sign(key, claims);

		assertFalse(service.verify(token, trusting(key)).emailVerified());
	}

	@Test
	void authorizationUrlIncludesTheClientRedirectAndState() {
		String url = service.authorizationUrl("xyz123");

		assertTrue(url.startsWith("https://accounts.google.com/o/oauth2/v2/auth?"));
		assertTrue(url.contains("client_id=" + CLIENT_ID));
		assertTrue(url.contains("state=xyz123"));
		assertTrue(url.contains("redirect_uri=https%3A%2F%2Fexample.ts.net"));
		assertTrue(url.contains("scope=openid"));
	}
}
