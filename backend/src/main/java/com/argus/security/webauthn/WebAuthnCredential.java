package com.argus.security.webauthn;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * A registered passkey (Story 2.2). One row per enrolled authenticator; the PIN
 * ({@code app_credential}) remains the fallback. Binary fields hold the WebAuthn credential id,
 * user handle, and COSE public key. {@code signatureCount} is advanced on each assertion for
 * clone detection.
 */
@Entity
@Table(name = "webauthn_credential")
public class WebAuthnCredential {

	@Id
	@Column(name = "credential_id", nullable = false, updatable = false)
	private byte[] credentialId;

	@Column(name = "user_handle", nullable = false, updatable = false)
	private byte[] userHandle;

	@Column(name = "public_key_cose", nullable = false, updatable = false)
	private byte[] publicKeyCose;

	@Column(name = "signature_count", nullable = false)
	private long signatureCount;

	@Column(name = "label", nullable = false)
	private String label;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt = Instant.now();

	@Column(name = "last_used_at")
	private Instant lastUsedAt;

	protected WebAuthnCredential() {
		// JPA
	}

	public WebAuthnCredential(byte[] credentialId, byte[] userHandle, byte[] publicKeyCose,
			long signatureCount, String label) {
		this.credentialId = credentialId;
		this.userHandle = userHandle;
		this.publicKeyCose = publicKeyCose;
		this.signatureCount = signatureCount;
		this.label = label;
	}

	public byte[] getCredentialId() {
		return credentialId;
	}

	public byte[] getUserHandle() {
		return userHandle;
	}

	public byte[] getPublicKeyCose() {
		return publicKeyCose;
	}

	public long getSignatureCount() {
		return signatureCount;
	}

	public void setSignatureCount(long signatureCount) {
		this.signatureCount = signatureCount;
	}

	public String getLabel() {
		return label;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public Instant getLastUsedAt() {
		return lastUsedAt;
	}

	public void setLastUsedAt(Instant lastUsedAt) {
		this.lastUsedAt = lastUsedAt;
	}
}
