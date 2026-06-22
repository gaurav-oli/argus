package com.argus.security;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import org.springframework.http.ResponseCookie;

/**
 * Single source for the session cookie: name + how it's built. {@code HttpOnly} (not readable by
 * JS), {@code Secure} (HTTPS — Tailscale provides it), {@code SameSite=Strict}. The single-origin
 * Tailscale deploy means Strict still sends the cookie on same-site navigations.
 */
public final class SessionCookie {

	public static final String NAME = "ARGUS_SESSION";

	private SessionCookie() {
	}

	/** Cookie carrying a live session id, expiring with the session TTL. */
	public static ResponseCookie issue(String sessionId, Duration ttl) {
		return base(sessionId).maxAge(ttl).build();
	}

	/** Cookie that immediately clears the session id (logout). */
	public static ResponseCookie expired() {
		return base("").maxAge(0).build();
	}

	/** Read the session id from the request's cookies, or {@code null} if absent. */
	public static String read(HttpServletRequest request) {
		if (request.getCookies() == null) {
			return null;
		}
		for (var cookie : request.getCookies()) {
			if (NAME.equals(cookie.getName())) {
				return cookie.getValue();
			}
		}
		return null;
	}

	private static ResponseCookie.ResponseCookieBuilder base(String value) {
		return ResponseCookie.from(NAME, value)
				.httpOnly(true)
				.secure(true)
				.sameSite("Strict")
				.path("/");
	}
}
