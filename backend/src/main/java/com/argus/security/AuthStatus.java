package com.argus.security;

/**
 * Auth state for the frontend's initial routing decision (setup vs login vs app).
 *
 * @param pinSet        whether the owner has completed first-launch PIN setup
 * @param authenticated whether the caller presented a valid session
 */
public record AuthStatus(boolean pinSet, boolean authenticated) {
}
