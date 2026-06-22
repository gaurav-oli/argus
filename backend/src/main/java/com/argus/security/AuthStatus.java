package com.argus.security;

/**
 * Auth state for the frontend's initial routing decision (setup vs login vs app), whether to offer
 * biometric unlock, and any active failed-attempt lockout (FR-38 / Story 2.6).
 *
 * @param pinSet                 whether the owner has completed first-launch PIN setup
 * @param authenticated          whether the caller presented a valid session
 * @param passkeyEnrolled        whether at least one WebAuthn passkey is registered (Story 2.2)
 * @param fullyLocked            whether PIN login is fully locked (needs another device — FR-38)
 * @param lockoutSecondsRemaining seconds left on a timed lockout (0 if none)
 */
public record AuthStatus(
		boolean pinSet,
		boolean authenticated,
		boolean passkeyEnrolled,
		boolean fullyLocked,
		long lockoutSecondsRemaining) {
}
