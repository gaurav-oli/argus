package com.argus.security;

import jakarta.servlet.http.Cookie;
import java.util.UUID;

/**
 * Test helper: mint a real signed-in session for an {@link AppUser}, bypassing the OAuth dance
 * entirely (PIN login is retired — Google Sign-In is the only real flow now). Phase 2 (multi-user)
 * made every portfolio table {@code @TenantId}-scoped to the signed-in person, so any integration
 * test that writes portfolio data needs a REAL {@link AppUser} behind its session, or writes fail
 * closed instead of silently landing on nobody's account.
 */
public final class TestUserSessions {

	private TestUserSessions() {
	}

	/** A brand-new, uniquely-identified {@link AppUser} with a live session cookie. */
	public static Cookie loginAsNewUser(AppUserRepository users, SessionStore sessions) {
		return loginAsNewUser(users, sessions, "Test device");
	}

	/** As {@link #loginAsNewUser(AppUserRepository, SessionStore)}, with a specific device label
	 * (e.g. to simulate one person's two different devices in a session-management test). */
	public static Cookie loginAsNewUser(AppUserRepository users, SessionStore sessions, String device) {
		String unique = UUID.randomUUID().toString();
		AppUser user = users.save(new AppUser("test-sub-" + unique, "test-" + unique + "@example.com",
				"Test User", null, false));
		return loginAs(sessions, user.getId(), device);
	}

	/** A new session for an ALREADY-EXISTING user — e.g. that same person signing in from a second
	 * device. */
	public static Cookie loginAs(SessionStore sessions, Long userId, String device) {
		String sessionId = sessions.create(device, userId);
		return new Cookie(SessionCookie.NAME, sessionId);
	}
}
