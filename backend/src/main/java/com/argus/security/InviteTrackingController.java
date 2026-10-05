package com.argus.security;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Records that an invite link was opened — called by the frontend the moment it sees {@code ?invite=}
 * in the URL, before the person has signed in (so this must be reachable with no session; allowlisted
 * in {@link SessionAuthFilter}). Always 204, even for an unknown/garbage token — this is a best-effort
 * signal for the admin's own invite list, not something worth leaking "valid token or not" over.
 */
@RestController
@RequestMapping("/api/invite")
public class InviteTrackingController {

	private final InvitedEmailRepository invites;

	public InviteTrackingController(InvitedEmailRepository invites) {
		this.invites = invites;
	}

	@PostMapping("/open")
	public ResponseEntity<Void> opened(@RequestParam(required = false) String token) {
		if (token != null && !token.isBlank()) {
			invites.findByToken(token).ifPresent(i -> {
				i.markOpened();
				invites.save(i);
			});
		}
		return ResponseEntity.noContent().build();
	}
}
