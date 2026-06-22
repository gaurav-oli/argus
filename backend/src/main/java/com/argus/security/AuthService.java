package com.argus.security;

import com.argus.common.ConflictException;
import com.argus.common.UnauthorizedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owner authentication: first-launch PIN setup, login (PIN → Redis session), and logout.
 * Keeps the {@link AuthController} thin and gives the auth rules one tested home.
 *
 * <p>The raw PIN is used only to hash/verify and is never stored, returned, or logged.
 */
@Service
public class AuthService {

	private final AppCredentialRepository credentials;
	private final PinHasher pinHasher;
	private final SessionStore sessions;

	public AuthService(AppCredentialRepository credentials, PinHasher pinHasher, SessionStore sessions) {
		this.credentials = credentials;
		this.pinHasher = pinHasher;
		this.sessions = sessions;
	}

	@Transactional(readOnly = true)
	public boolean isPinSet() {
		return credentials.existsSingleton();
	}

	/**
	 * First-launch PIN setup. Stores the Argon2id hash.
	 *
	 * @throws ConflictException if a PIN already exists (use the authenticated change-PIN path —
	 *                           out of scope for Story 2.1)
	 */
	@Transactional
	public void setupPin(String rawPin) {
		if (credentials.existsSingleton()) {
			throw new ConflictException("A PIN is already set");
		}
		credentials.save(new AppCredential(pinHasher.hash(rawPin)));
	}

	/**
	 * Verify the PIN and start a session.
	 *
	 * @return the new opaque session id
	 * @throws UnauthorizedException if no PIN is set or the PIN does not match
	 */
	@Transactional(readOnly = true)
	public String login(String rawPin) {
		AppCredential credential = credentials.findSingleton()
				.orElseThrow(() -> new UnauthorizedException("Invalid PIN"));
		// TODO(Story 2.6): record failed attempts here and apply escalating lockout
		// (3 → 30s, 5 → 10m + alert, 10 → full lock) before/around this check.
		if (!pinHasher.matches(rawPin, credential.getPinHash())) {
			throw new UnauthorizedException("Invalid PIN");
		}
		return sessions.create();
	}

	public void logout(String sessionId) {
		sessions.destroy(sessionId);
	}

	public boolean isAuthenticated(String sessionId) {
		return sessions.validate(sessionId);
	}
}
