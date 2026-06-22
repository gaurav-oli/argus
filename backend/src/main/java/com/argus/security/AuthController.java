package com.argus.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Owner authentication endpoints (Story 2.1). All paths under {@code /api/auth} except the
 * authenticated ones are allowlisted in {@link SessionAuthFilter}.
 *
 * <ul>
 *   <li>{@code GET  /api/auth/status} — pinSet / authenticated (drives frontend routing)</li>
 *   <li>{@code POST /api/auth/pin}    — first-launch PIN setup (201; 409 if already set)</li>
 *   <li>{@code POST /api/auth/login}  — PIN → Redis session + cookie (200; 401 on wrong PIN)</li>
 *   <li>{@code POST /api/auth/logout} — destroy session + clear cookie (200)</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

	private final AuthService auth;
	private final SessionStore sessions;

	public AuthController(AuthService auth, SessionStore sessions) {
		this.auth = auth;
		this.sessions = sessions;
	}

	@GetMapping("/status")
	public AuthStatus status(HttpServletRequest request) {
		boolean authenticated = auth.isAuthenticated(SessionCookie.read(request));
		return new AuthStatus(auth.isPinSet(), authenticated);
	}

	@PostMapping("/pin")
	public ResponseEntity<Void> setupPin(@Valid @RequestBody PinRequest body) {
		auth.setupPin(body.pin());
		return ResponseEntity.status(HttpStatus.CREATED).build();
	}

	@PostMapping("/login")
	public ResponseEntity<AuthStatus> login(@Valid @RequestBody PinRequest body) {
		String sessionId = auth.login(body.pin());
		ResponseCookie cookie = SessionCookie.issue(sessionId, sessions.ttl());
		return ResponseEntity.ok()
				.header(HttpHeaders.SET_COOKIE, cookie.toString())
				.body(new AuthStatus(true, true));
	}

	@PostMapping("/logout")
	public ResponseEntity<Void> logout(HttpServletRequest request) {
		auth.logout(SessionCookie.read(request));
		return ResponseEntity.noContent()
				.header(HttpHeaders.SET_COOKIE, SessionCookie.expired().toString())
				.build();
	}
}
