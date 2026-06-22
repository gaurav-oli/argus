package com.argus.security;

import com.argus.common.ConflictException;
import com.argus.common.UnauthorizedException;
import org.springframework.dao.DataIntegrityViolationException;
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
	private final LockoutService lockout;

	public AuthService(AppCredentialRepository credentials, PinHasher pinHasher, SessionStore sessions,
			LockoutService lockout) {
		this.credentials = credentials;
		this.pinHasher = pinHasher;
		this.sessions = sessions;
		this.lockout = lockout;
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
		try {
			credentials.save(new AppCredential(pinHasher.hash(rawPin)));
		} catch (DataIntegrityViolationException ex) {
			// Two concurrent first-launch setups both passed existsSingleton(); the PK / CHECK(id=1)
			// rejects the loser. Surface the same 409 as the sequential case, not a 500.
			throw new ConflictException("A PIN is already set");
		}
	}

	/**
	 * Verify the PIN and start a session.
	 *
	 * @return the new opaque session id
	 * @throws UnauthorizedException if no PIN is set or the PIN does not match
	 */
	// Not @Transactional: a single read + Redis writes (not enrolled in the JPA tx).
	public String login(String rawPin, String device) {
		// Escalating failed-attempt lockout (FR-38 / Story 2.6): refuse before any PIN check while
		// locked; record failures (which may itself throw a LockedException at a threshold); reset
		// on success.
		lockout.assertNotLocked();
		AppCredential credential = credentials.findSingleton().orElse(null);
		if (credential == null || !pinHasher.matches(rawPin, credential.getPinHash())) {
			lockout.recordFailure(); // throws LockedException if this failure crosses a threshold
			throw new UnauthorizedException("Invalid PIN");
		}
		lockout.reset();
		return sessions.create(device);
	}

	/** Clear a failed-attempt lockout — called from an already-authenticated device (FR-38). */
	public void clearLockout() {
		lockout.clear();
	}

	public LockoutService.Lockout lockoutState() {
		return lockout.current();
	}

	public void logout(String sessionId) {
		sessions.destroy(sessionId);
	}

	public boolean isAuthenticated(String sessionId) {
		return sessions.validate(sessionId);
	}
}
