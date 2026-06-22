package com.argus.security.webauthn;

import com.yubico.webauthn.CredentialRepository;
import com.yubico.webauthn.RegisteredCredential;
import com.yubico.webauthn.data.ByteArray;
import com.yubico.webauthn.data.PublicKeyCredentialDescriptor;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * Adapts Argus's {@link WebAuthnCredentialRepository} to Yubico's {@link CredentialRepository}.
 * Argus is single-user, so there is exactly one logical user: {@link #USERNAME} with the fixed,
 * opaque {@link #USER_HANDLE}. Multiple passkeys can be registered to that one user.
 */
@Component
public class ArgusCredentialRepository implements CredentialRepository {

	/** The single Argus user. */
	public static final String USERNAME = "argus";

	/** Stable, opaque WebAuthn user handle for the single user (no PII). */
	public static final ByteArray USER_HANDLE = new ByteArray(new byte[] {
			(byte) 0xA0, (byte) 0x9C, 0x00, 0x01, 0x40, 0x00, (byte) 0x80, 0x00,
			0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x01 });

	private final WebAuthnCredentialRepository credentials;

	public ArgusCredentialRepository(WebAuthnCredentialRepository credentials) {
		this.credentials = credentials;
	}

	@Override
	public Set<PublicKeyCredentialDescriptor> getCredentialIdsForUsername(String username) {
		if (!USERNAME.equals(username)) {
			return Set.of();
		}
		return credentials.findByUserHandle(USER_HANDLE.getBytes()).stream()
				.map(c -> PublicKeyCredentialDescriptor.builder()
						.id(new ByteArray(c.getCredentialId()))
						.build())
				.collect(Collectors.toSet());
	}

	@Override
	public Optional<ByteArray> getUserHandleForUsername(String username) {
		return USERNAME.equals(username) ? Optional.of(USER_HANDLE) : Optional.empty();
	}

	@Override
	public Optional<String> getUsernameForUserHandle(ByteArray userHandle) {
		return USER_HANDLE.equals(userHandle) ? Optional.of(USERNAME) : Optional.empty();
	}

	@Override
	public Optional<RegisteredCredential> lookup(ByteArray credentialId, ByteArray userHandle) {
		return credentials.findById(credentialId.getBytes())
				.filter(c -> USER_HANDLE.equals(new ByteArray(c.getUserHandle())))
				.map(ArgusCredentialRepository::toRegisteredCredential);
	}

	@Override
	public Set<RegisteredCredential> lookupAll(ByteArray credentialId) {
		return credentials.findById(credentialId.getBytes())
				.map(ArgusCredentialRepository::toRegisteredCredential)
				.map(Set::of)
				.orElseGet(Set::of);
	}

	private static RegisteredCredential toRegisteredCredential(WebAuthnCredential c) {
		return RegisteredCredential.builder()
				.credentialId(new ByteArray(c.getCredentialId()))
				.userHandle(new ByteArray(c.getUserHandle()))
				.publicKeyCose(new ByteArray(c.getPublicKeyCose()))
				.signatureCount(c.getSignatureCount())
				.build();
	}
}
