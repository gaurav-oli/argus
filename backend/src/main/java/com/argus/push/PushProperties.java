package com.argus.push;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Web Push VAPID configuration ({@code argus.push.vapid.*}, Epic 8 / FR-17). The keypair is a stable
 * EC P-256 pair: the {@code publicKey} is shared with the browser and baked into every subscription
 * (so it must never change once devices subscribe); the {@code privateKey} is a secret the backend
 * signs push requests with. Both are base64url (no padding). With no keys set, push is simply
 * disabled ({@link #isConfigured()} is false) and the rest of Argus runs unaffected.
 *
 * @param publicKey  base64url EC P-256 public key (non-secret; from {@code ARGUS_PUSH_VAPID_PUBLIC})
 * @param privateKey base64url EC P-256 private scalar (secret; from {@code ARGUS_PUSH_VAPID_PRIVATE})
 * @param subject    VAPID {@code sub} contact — a {@code mailto:} or https URL (RFC 8292)
 */
@ConfigurationProperties("argus.push.vapid")
public record PushProperties(
		@DefaultValue("") String publicKey,
		@DefaultValue("") String privateKey,
		@DefaultValue("mailto:argus@localhost") String subject) {

	/** True only when both keys are present — push is sent only then. */
	public boolean isConfigured() {
		return !publicKey.isBlank() && !privateKey.isBlank();
	}
}
