package com.argus.security;

import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Hashes and verifies the owner PIN with Argon2id (Decision 5). A 4–6 digit PIN is
 * low-entropy, so a memory-hard KDF plus the Story 2.6 lockout are the real brute-force
 * defenses. {@link #matches} is constant-time (delegated to the encoder).
 *
 * <p>The raw PIN never leaves this layer and is never logged.
 */
@Component
public class PinHasher {

	private final Argon2PasswordEncoder encoder = Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8();

	public String hash(String rawPin) {
		return encoder.encode(rawPin);
	}

	public boolean matches(String rawPin, String storedHash) {
		return encoder.matches(rawPin, storedHash);
	}
}
