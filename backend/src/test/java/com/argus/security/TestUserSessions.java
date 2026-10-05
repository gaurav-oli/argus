package com.argus.security;

import jakarta.servlet.http.Cookie;
import java.util.UUID;

/**
 * Test helper: mint a real signed-in session for a fresh {@link AppUser}, bypassing the OAuth
 * dance entirely. Phase 2 (multi-user) made every portfolio table {@code @TenantId}-scoped to the
 * signed-in person, so any integration test that writes portfolio data needs a REAL {@link AppUser}
 * behind its session — the old PIN login (still wired for backward compatibility, but no longer
 * reachable from the UI) creates a session with no user attached, which now fails closed instead
 * of silently writing unowned rows.
 */
public final class TestUserSessions {

	private TestUserSessions() {
	}

	/** A brand-new, uniquely-identified {@link AppUser} with a live session cookie. */
	public static Cookie loginAsNewUser(AppUserRepository users, SessionStore sessions) {
		String unique = UUID.randomUUID().toString();
		AppUser user = users.save(new AppUser("test-sub-" + unique, "test-" + unique + "@example.com",
				"Test User", null, false));
		String sessionId = sessions.create("Test device", user.getId());
		return new Cookie(SessionCookie.NAME, sessionId);
	}
}
