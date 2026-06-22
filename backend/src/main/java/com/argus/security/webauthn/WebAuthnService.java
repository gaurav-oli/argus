package com.argus.security.webauthn;

import com.argus.common.BadRequestException;
import com.argus.common.ConflictException;
import com.argus.common.UnauthorizedException;
import com.argus.security.SessionStore;
import org.springframework.dao.DataIntegrityViolationException;
import com.yubico.webauthn.AssertionRequest;
import com.yubico.webauthn.AssertionResult;
import com.yubico.webauthn.FinishAssertionOptions;
import com.yubico.webauthn.FinishRegistrationOptions;
import com.yubico.webauthn.RegistrationResult;
import com.yubico.webauthn.RelyingParty;
import com.yubico.webauthn.StartAssertionOptions;
import com.yubico.webauthn.StartRegistrationOptions;
import com.yubico.webauthn.data.AuthenticatorSelectionCriteria;
import com.yubico.webauthn.data.PublicKeyCredentialCreationOptions;
import com.yubico.webauthn.data.ResidentKeyRequirement;
import com.yubico.webauthn.data.UserIdentity;
import com.yubico.webauthn.data.UserVerificationRequirement;
import com.yubico.webauthn.exception.AssertionFailedException;
import com.yubico.webauthn.exception.RegistrationFailedException;
import java.io.IOException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * WebAuthn ceremonies (Story 2.2). Registration is session-gated (enroll while logged in via PIN);
 * assertion (unlock) is pre-session and, on success, mints the same Redis session as PIN login.
 * The server-generated challenge/request is stashed in Redis between start and finish (single-use,
 * short TTL) and never trusted from the client.
 */
@Service
public class WebAuthnService {

	private static final Duration CEREMONY_TTL = Duration.ofMinutes(5);
	private static final String REG_KEY = "argus:webauthn:reg:";
	private static final String ASSERT_KEY = "argus:webauthn:assert:";

	private final RelyingParty rp;
	private final WebAuthnCredentialRepository credentials;
	private final SessionStore sessions;
	private final StringRedisTemplate redis;
	private final SecureRandom random = new SecureRandom();
	private final Base64.Encoder b64 = Base64.getUrlEncoder().withoutPadding();

	public WebAuthnService(RelyingParty rp, WebAuthnCredentialRepository credentials,
			SessionStore sessions, StringRedisTemplate redis) {
		this.rp = rp;
		this.credentials = credentials;
		this.sessions = sessions;
		this.redis = redis;
	}

	public boolean anyPasskeyEnrolled() {
		return credentials.count() > 0;
	}

	public java.util.List<WebAuthnCredential> listCredentials() {
		return credentials.findAll();
	}

	@Transactional
	public void revoke(byte[] credentialId) {
		credentials.deleteById(credentialId);
	}

	// ---- Registration (session-gated: the caller already proved the PIN) ----

	/**
	 * Build creation options, stash them under a fresh ceremony id, return both. Keyed by a
	 * per-ceremony id (not the session) so concurrent enrollments don't clobber each other.
	 * Yubico auto-populates {@code excludeCredentials} from the adapter, so an already-registered
	 * authenticator won't be enrolled twice.
	 */
	public Started startRegistration() {
		UserIdentity user = UserIdentity.builder()
				.name(ArgusCredentialRepository.USERNAME)
				.displayName("Argus")
				.id(ArgusCredentialRepository.USER_HANDLE)
				.build();
		PublicKeyCredentialCreationOptions options = rp.startRegistration(StartRegistrationOptions.builder()
				.user(user)
				.authenticatorSelection(AuthenticatorSelectionCriteria.builder()
						.residentKey(ResidentKeyRequirement.PREFERRED)
						.userVerification(UserVerificationRequirement.REQUIRED)
						.build())
				.build());
		String ceremonyId = newCeremonyId();
		try {
			redis.opsForValue().set(REG_KEY + ceremonyId, options.toJson(), CEREMONY_TTL);
			return new Started(ceremonyId, options.toCredentialsCreateJson());
		} catch (IOException ex) {
			throw new BadRequestException("Could not encode registration options");
		}
	}

	/** Verify the attestation against the stashed options and persist the new passkey. */
	@Transactional
	public void finishRegistration(String ceremonyId, String credentialJson, String label) {
		if (ceremonyId == null || ceremonyId.isBlank()) {
			throw new BadRequestException("No registration in progress");
		}
		String stashed = redis.opsForValue().get(REG_KEY + ceremonyId);
		if (stashed == null) {
			throw new BadRequestException("No registration in progress");
		}
		try {
			PublicKeyCredentialCreationOptions options = PublicKeyCredentialCreationOptions.fromJson(stashed);
			RegistrationResult result = rp.finishRegistration(FinishRegistrationOptions.builder()
					.request(options)
					.response(com.yubico.webauthn.data.PublicKeyCredential.parseRegistrationResponseJson(credentialJson))
					.build());
			credentials.saveAndFlush(new WebAuthnCredential(
					result.getKeyId().getId().getBytes(),
					ArgusCredentialRepository.USER_HANDLE.getBytes(),
					result.getPublicKeyCose().getBytes(),
					result.getSignatureCount(),
					(label == null || label.isBlank()) ? "Passkey" : label.strip()));
		} catch (IOException | RegistrationFailedException ex) {
			throw new BadRequestException("Passkey registration failed");
		} catch (DataIntegrityViolationException ex) {
			throw new ConflictException("That passkey is already registered");
		} finally {
			redis.delete(REG_KEY + ceremonyId);
		}
	}

	// ---- Assertion (unlock: pre-session) ----

	/** Build assertion options, stash them under a fresh ceremony id, return both. */
	public Started startAssertion() {
		AssertionRequest request = rp.startAssertion(StartAssertionOptions.builder()
				.username(ArgusCredentialRepository.USERNAME)
				.userVerification(UserVerificationRequirement.REQUIRED)
				.build());
		String ceremonyId = newCeremonyId();
		try {
			redis.opsForValue().set(ASSERT_KEY + ceremonyId, request.toJson(), CEREMONY_TTL);
			return new Started(ceremonyId, request.toCredentialsGetJson());
		} catch (IOException ex) {
			throw new BadRequestException("Could not encode assertion options");
		}
	}

	/**
	 * Verify the assertion against the stashed request; on success advance the signature counter
	 * and start a session. Returns the new session id.
	 */
	@Transactional
	public String finishAssertion(String ceremonyId, String credentialJson, String device) {
		if (ceremonyId == null || ceremonyId.isBlank()) {
			throw new UnauthorizedException("No unlock in progress");
		}
		String stashed = redis.opsForValue().get(ASSERT_KEY + ceremonyId);
		if (stashed == null) {
			throw new UnauthorizedException("No unlock in progress");
		}
		try {
			AssertionRequest request = AssertionRequest.fromJson(stashed);
			AssertionResult result = rp.finishAssertion(FinishAssertionOptions.builder()
					.request(request)
					.response(com.yubico.webauthn.data.PublicKeyCredential.parseAssertionResponseJson(credentialJson))
					.build());
			if (!result.isSuccess()) {
				throw new UnauthorizedException("Biometric unlock failed");
			}
			// Fail closed: the credential must still exist (could be revoked mid-ceremony). Advancing
			// the signature counter is mandatory for clone detection, not best-effort.
			WebAuthnCredential stored = credentials.findById(result.getCredential().getCredentialId().getBytes())
					.orElseThrow(() -> new UnauthorizedException("Biometric unlock failed"));
			stored.setSignatureCount(result.getSignatureCount());
			stored.setLastUsedAt(Instant.now());
			credentials.save(stored);
			return sessions.create(device);
		} catch (IOException | AssertionFailedException ex) {
			throw new UnauthorizedException("Biometric unlock failed");
		} finally {
			redis.delete(ASSERT_KEY + ceremonyId);
		}
	}

	private String newCeremonyId() {
		byte[] bytes = new byte[32];
		random.nextBytes(bytes);
		return b64.encodeToString(bytes);
	}

	/** Result of starting an assertion: the ceremony handle + the browser get() options JSON. */
	public record Started(String ceremonyId, String optionsJson) {
	}
}
