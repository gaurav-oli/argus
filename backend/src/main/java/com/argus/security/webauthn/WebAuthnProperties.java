package com.argus.security.webauthn;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * WebAuthn / passkey configuration ({@code argus.security.webauthn.*}).
 *
 * @param rpId    Relying Party ID — the effective domain. {@code localhost} in dev; the tailnet
 *                host on the Mini (e.g. {@code mini.<tailnet>.ts.net}). Must be a registrable
 *                suffix of every {@link #origins} entry.
 * @param rpName  human-readable RP name shown by the platform authenticator.
 * @param origins allowed WebAuthn origins (full scheme+host), e.g. {@code http://localhost:3000}
 *                in dev, {@code https://mini.<tailnet>.ts.net} on the Mini. Set via
 *                {@code ARGUS_SECURITY_WEBAUTHN_ORIGINS} (comma-separated).
 */
@ConfigurationProperties("argus.security.webauthn")
public record WebAuthnProperties(
		@DefaultValue("localhost") String rpId,
		@DefaultValue("Argus") String rpName,
		@DefaultValue("http://localhost:3000") List<String> origins) {
}
