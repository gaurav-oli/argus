package com.argus.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Session endpoints shared by every signed-in person, regardless of how they signed in — Google is
 * now the only path (see {@link GoogleAuthController}); the PIN/WebAuthn flow this used to also serve
 * is retired and removed.
 *
 * <ul>
 *   <li>{@code GET    /api/auth/status}         — authenticated + who, for the frontend's routing</li>
 *   <li>{@code POST   /api/auth/logout}          — destroy session + clear cookie</li>
 *   <li>{@code GET    /api/auth/sessions}        — list this person's active sessions (FR-39)</li>
 *   <li>{@code DELETE /api/auth/sessions/{{handle}}} — remotely terminate one of them</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

	private final SessionStore sessions;
	private final SecurityProperties securityProperties;
	private final CurrentUserService currentUser;

	public AuthController(SessionStore sessions, SecurityProperties securityProperties,
			CurrentUserService currentUser) {
		this.sessions = sessions;
		this.securityProperties = securityProperties;
		this.currentUser = currentUser;
	}

	@GetMapping("/status")
	public AuthStatus status(HttpServletRequest request) {
		boolean authenticated = sessions.validate(SessionCookie.read(request));
		AuthStatus.UserView user = authenticated
				? currentUser.resolve(request).map(AuthStatus.UserView::from).orElse(null)
				: null;
		return new AuthStatus(authenticated, user);
	}

	@PostMapping("/logout")
	public ResponseEntity<Void> logout(HttpServletRequest request) {
		sessions.destroy(SessionCookie.read(request));
		return ResponseEntity.noContent()
				.header(HttpHeaders.SET_COOKIE, SessionCookie.expired(securityProperties.cookieSecure()).toString())
				.build();
	}

	/** List active sessions (FR-39 / Story 2.7), marking the caller's own. Session-gated. */
	@GetMapping("/sessions")
	public java.util.List<SessionStore.SessionInfo> sessions(HttpServletRequest request) {
		return sessions.list(SessionCookie.read(request));
	}

	/**
	 * Remotely terminate a session by its handle (FR-39). Session-gated, so any signed-in device
	 * (e.g. another Tailscale device) can kill a lost device's session; the target is rejected on
	 * its next request (the filter validates every call), well within the 5s target.
	 */
	@DeleteMapping("/sessions/{handle}")
	public ResponseEntity<Void> revokeSession(@PathVariable String handle) {
		sessions.revokeByHandle(handle);
		return ResponseEntity.noContent().build();
	}
}
