package com.argus.security.webauthn;

import com.argus.common.BadRequestException;
import com.argus.security.SecurityProperties;
import com.argus.security.SessionCookie;
import com.argus.security.SessionStore;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * WebAuthn / passkey endpoints (Story 2.2).
 *
 * <ul>
 *   <li>{@code POST /register/start|finish} — enroll a passkey (session-gated: PIN proven first)</li>
 *   <li>{@code POST /login/start|finish} — biometric unlock (allowlisted in {@code SessionAuthFilter})</li>
 *   <li>{@code GET/DELETE /credentials} — list / revoke enrolled passkeys (session-gated)</li>
 * </ul>
 *
 * The ceremony option/response bodies are WebAuthn JSON produced/consumed by the Yubico library,
 * so they're passed through as raw JSON strings rather than re-serialized by Spring.
 */
@RestController
@RequestMapping("/api/auth/webauthn")
public class WebAuthnController {

	/** Header carrying the assertion ceremony handle between login/start and login/finish. */
	static final String CEREMONY_HEADER = "X-Argus-Ceremony";

	private final WebAuthnService webAuthn;
	private final SessionStore sessions;
	private final SecurityProperties securityProperties;

	public WebAuthnController(WebAuthnService webAuthn, SessionStore sessions,
			SecurityProperties securityProperties) {
		this.webAuthn = webAuthn;
		this.sessions = sessions;
		this.securityProperties = securityProperties;
	}

	@PostMapping("/register/start")
	public ResponseEntity<String> registerStart() {
		WebAuthnService.Started started = webAuthn.startRegistration();
		return ResponseEntity.ok()
				.contentType(MediaType.APPLICATION_JSON)
				.header(CEREMONY_HEADER, started.ceremonyId())
				.body(started.optionsJson());
	}

	@PostMapping("/register/finish")
	public ResponseEntity<Void> registerFinish(@RequestHeader(CEREMONY_HEADER) String ceremonyId,
			@RequestParam(required = false) String label, @RequestBody String credentialJson) {
		webAuthn.finishRegistration(ceremonyId, credentialJson, label);
		return ResponseEntity.noContent().build();
	}

	@PostMapping("/login/start")
	public ResponseEntity<String> loginStart() {
		WebAuthnService.Started started = webAuthn.startAssertion();
		return ResponseEntity.ok()
				.contentType(MediaType.APPLICATION_JSON)
				.header(CEREMONY_HEADER, started.ceremonyId())
				.body(started.optionsJson());
	}

	@PostMapping("/login/finish")
	public ResponseEntity<Void> loginFinish(@RequestHeader(CEREMONY_HEADER) String ceremonyId,
			@RequestBody String credentialJson, jakarta.servlet.http.HttpServletRequest request) {
		String sessionId = webAuthn.finishAssertion(ceremonyId, credentialJson,
				com.argus.security.DeviceLabel.from(request.getHeader("User-Agent")));
		ResponseCookie cookie = SessionCookie.issue(sessionId, sessions.cookieMaxAge(), securityProperties.cookieSecure());
		return ResponseEntity.noContent()
				.header(HttpHeaders.SET_COOKIE, cookie.toString())
				.build();
	}

	@GetMapping("/credentials")
	public List<PasskeyInfo> list() {
		return webAuthn.listCredentials().stream().map(PasskeyInfo::of).toList();
	}

	@DeleteMapping("/credentials/{id}")
	public ResponseEntity<Void> revoke(@PathVariable String id) {
		byte[] credentialId;
		try {
			credentialId = Base64.getUrlDecoder().decode(id);
		} catch (IllegalArgumentException ex) {
			throw new BadRequestException("Invalid credential id");
		}
		webAuthn.revoke(credentialId);
		return ResponseEntity.noContent().build();
	}

	/** Public view of an enrolled passkey (no key material). */
	public record PasskeyInfo(String id, String label, Instant createdAt, Instant lastUsedAt) {

		static PasskeyInfo of(WebAuthnCredential c) {
			return new PasskeyInfo(
					Base64.getUrlEncoder().withoutPadding().encodeToString(c.getCredentialId()),
					c.getLabel(), c.getCreatedAt(), c.getLastUsedAt());
		}
	}
}
