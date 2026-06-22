package com.argus.security;

/**
 * Auth state for the frontend's initial routing decision (setup vs login vs app) and whether to
 * offer biometric unlock.
 *
 * @param pinSet          whether the owner has completed first-launch PIN setup
 * @param authenticated   whether the caller presented a valid session
 * @param passkeyEnrolled whether at least one WebAuthn passkey is registered (Story 2.2) — the
 *                        lock screen uses this to show the Face/Touch ID option
 */
public record AuthStatus(boolean pinSet, boolean authenticated, boolean passkeyEnrolled) {
}
