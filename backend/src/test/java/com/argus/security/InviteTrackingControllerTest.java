package com.argus.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/** Records an invite link's first open — never leaks whether a token is valid (always 204). */
class InviteTrackingControllerTest {

	private final InvitedEmailRepository invites = mock(InvitedEmailRepository.class);
	private final InviteTrackingController controller = new InviteTrackingController(invites);

	@Test
	void aKnownTokenMarksTheInviteOpened() {
		InvitedEmail invite = new InvitedEmail("friend@gmail.com", "admin");
		when(invites.findByToken("tok123")).thenReturn(Optional.of(invite));

		ResponseEntity<Void> res = controller.opened("tok123");

		assertEquals(HttpStatus.NO_CONTENT, res.getStatusCode());
		assertNotNull(invite.getOpenedAt());
		verify(invites).save(invite);
	}

	@Test
	void anUnknownTokenIsStillA204NotAnError() {
		when(invites.findByToken("bogus")).thenReturn(Optional.empty());

		ResponseEntity<Void> res = controller.opened("bogus");

		assertEquals(HttpStatus.NO_CONTENT, res.getStatusCode());
		verify(invites, never()).save(org.mockito.ArgumentMatchers.any());
	}

	@Test
	void aMissingTokenIsStillA204() {
		ResponseEntity<Void> res = controller.opened(null);

		assertEquals(HttpStatus.NO_CONTENT, res.getStatusCode());
	}
}
