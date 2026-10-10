package com.argus.security;

import com.argus.common.NotFoundException;
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
 *   <li>{@code GET    /api/auth/sessions}        — list <b>this person's</b> active sessions (FR-39 / S-A2)</li>
 *   <li>{@code DELETE /api/auth/sessions/{{handle}}} — remotely terminate one of <b>theirs</b></li>
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
		// S-A6 / M2: never report authenticated=true with user=null — require a resolvable AppUser.
		return currentUser.resolve(request)
				.map(u -> new AuthStatus(true, AuthStatus.UserView.from(u)))
				.orElseGet(() -> new AuthStatus(false, null));
	}

	@PostMapping("/logout")
	public ResponseEntity<Void> logout(HttpServletRequest request) {
		sessions.destroy(SessionCookie.read(request));
		return ResponseEntity.noContent()
				.header(HttpHeaders.SET_COOKIE, SessionCookie.expired(securityProperties.cookieSecure()).toString())
				.build();
	}

	/** List this person's active sessions (FR-39 / Story 2.7 / S-A2), marking the caller's own. */
	@GetMapping("/sessions")
	public java.util.List<SessionStore.SessionInfo> sessions(HttpServletRequest request) {
		AppUser user = currentUser.require(request);
		return sessions.list(SessionCookie.read(request), user.getId());
	}

	/**
	 * Remotely terminate one of <b>this person's</b> sessions by handle (FR-39 / S-A2). Another
	 * user's handle is indistinguishable from unknown (404) — no cross-user kill.
	 */
	@DeleteMapping("/sessions/{handle}")
	public ResponseEntity<Void> revokeSession(@PathVariable String handle, HttpServletRequest request) {
		AppUser user = currentUser.require(request);
		if (!sessions.revokeByHandle(handle, user.getId())) {
			throw new NotFoundException("session", handle);
		}
		return ResponseEntity.noContent().build();
	}
}
