package com.argus.security;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Google Sign-In (multi-user). Two pre-session endpoints: {@code /api/auth/google/login} redirects
 * the browser to Google's consent screen, {@code /api/login/oauth2/code/google} is where Google
 * sends it back (this exact path is what's registered as the "Authorized redirect URI" in Google
 * Cloud Console — changing it here means re-registering it there too).
 *
 * <p>Provisioning rule: a Google account only ever becomes an {@link AppUser} if its email is on
 * {@link InvitedEmailRepository}'s allowlist — anyone else gets a clear "not invited" redirect, never
 * a generic error. The one email configured as {@code ARGUS_ADMIN_EMAIL} is promoted to admin the
 * moment ITS account is first created, and never again afterward.
 */
@RestController
public class GoogleAuthController {

	private static final Logger log = LoggerFactory.getLogger(GoogleAuthController.class);
	static final String STATE_COOKIE = "ARGUS_OAUTH_STATE"; // package-private: referenced directly by tests

	private final GoogleOAuthService google;
	private final GoogleOAuthProperties props;
	private final AdminProperties admin;
	private final InvitedEmailRepository invited;
	private final AppUserRepository users;
	private final SessionStore sessions;
	private final SecurityProperties securityProperties;

	public GoogleAuthController(GoogleOAuthService google, GoogleOAuthProperties props, AdminProperties admin,
			InvitedEmailRepository invited, AppUserRepository users, SessionStore sessions,
			SecurityProperties securityProperties) {
		this.google = google;
		this.props = props;
		this.admin = admin;
		this.invited = invited;
		this.users = users;
		this.sessions = sessions;
		this.securityProperties = securityProperties;
	}

	@GetMapping("/api/auth/google/login")
	public ResponseEntity<Void> login() {
		if (!props.configured()) {
			return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
		}
		String state = newState();
		ResponseCookie stateCookie = ResponseCookie.from(STATE_COOKIE, state)
				.httpOnly(true).secure(securityProperties.cookieSecure())
				// Lax, not Strict: this cookie must survive the top-level cross-site redirect back
				// from accounts.google.com, which Strict would drop.
				.sameSite("Lax").path("/").maxAge(Duration.ofMinutes(10)).build();
		return ResponseEntity.status(HttpStatus.FOUND)
				.header(HttpHeaders.LOCATION, google.authorizationUrl(state))
				.header(HttpHeaders.SET_COOKIE, stateCookie.toString())
				.build();
	}

	@GetMapping("/api/login/oauth2/code/google")
	public ResponseEntity<Void> callback(@RequestParam(required = false) String code,
			@RequestParam(required = false) String state, @RequestParam(required = false) String error,
			HttpServletRequest request) {
		String expectedState = readCookie(request, STATE_COOKIE);
		ResponseCookie clearState = ResponseCookie.from(STATE_COOKIE, "")
				.httpOnly(true).secure(securityProperties.cookieSecure()).sameSite("Lax").path("/").maxAge(0).build();

		if (error != null || code == null || expectedState == null || !expectedState.equals(state)) {
			log.warn("Google sign-in rejected before it reached Google's identity: error={}, codePresent={}, stateOk={}",
					error, code != null, expectedState != null && expectedState.equals(state));
			return redirect("/?auth=failed", clearState);
		}
		try {
			GoogleIdentity identity = google.verify(google.exchangeCodeForIdToken(code));
			if (!identity.emailVerified()) {
				log.warn("Google sign-in rejected: email not verified on the Google side");
				return redirect("/?auth=failed", clearState);
			}
			String email = InvitedEmail.normalize(identity.email());
			AppUser user = provision(identity, email);
			if (user == null) {
				log.info("Google sign-in refused — {} is not on the invite list", email);
				return redirect("/?auth=not_invited", clearState);
			}

			String device = DeviceLabel.from(request.getHeader("User-Agent"));
			String sessionId = sessions.create(device, user.getId());
			ResponseCookie sessionCookie = SessionCookie.issue(sessionId, sessions.cookieMaxAge(), securityProperties.cookieSecure());
			return ResponseEntity.status(HttpStatus.FOUND)
					.header(HttpHeaders.LOCATION, "/")
					.header(HttpHeaders.SET_COOKIE, sessionCookie.toString())
					.header(HttpHeaders.SET_COOKIE, clearState.toString())
					.build();
		}
		catch (GoogleAuthException ex) {
			log.warn("Google sign-in failed: {}", ex.getMessage());
			return redirect("/?auth=failed", clearState);
		}
	}

	/** Look up the existing account by Google's stable subject id, or — only for an invited email —
	 * provision a new one. Returns null when the email isn't invited and no account exists yet. */
	private AppUser provision(GoogleIdentity identity, String email) {
		Optional<AppUser> existing = users.findByGoogleSub(identity.sub());
		AppUser user;
		if (existing.isPresent()) {
			user = existing.get();
			user.refreshProfile(identity.name(), identity.pictureUrl());
		}
		else {
			if (invited.findById(email).isEmpty()) {
				return null;
			}
			boolean isAdmin = !admin.email().isBlank() && admin.email().equalsIgnoreCase(email);
			user = new AppUser(identity.sub(), email, identity.name(), identity.pictureUrl(), isAdmin);
		}
		user.recordLogin();
		return users.save(user);
	}

	private static ResponseEntity<Void> redirect(String location, ResponseCookie clearState) {
		return ResponseEntity.status(HttpStatus.FOUND)
				.header(HttpHeaders.LOCATION, location)
				.header(HttpHeaders.SET_COOKIE, clearState.toString())
				.build();
	}

	private static String readCookie(HttpServletRequest request, String name) {
		if (request.getCookies() == null) {
			return null;
		}
		for (Cookie c : request.getCookies()) {
			if (name.equals(c.getName())) {
				return c.getValue();
			}
		}
		return null;
	}

	private static String newState() {
		byte[] bytes = new byte[24];
		new SecureRandom().nextBytes(bytes);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
	}
}
