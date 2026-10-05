package com.argus.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** The invite-link token and open-tracking behavior (admin-sent email invites). */
class InvitedEmailTest {

	@Test
	void ensureTokenGeneratesARealOneOnFirstCall() {
		InvitedEmail invite = new InvitedEmail("friend@gmail.com", "admin");

		String token = invite.ensureToken();

		assertNotNull(token);
		assertFalse(token.isBlank());
	}

	@Test
	void ensureTokenIsIdempotent() {
		InvitedEmail invite = new InvitedEmail("friend@gmail.com", "admin");

		String first = invite.ensureToken();
		String second = invite.ensureToken();

		assertEquals(first, second, "re-sending shouldn't invalidate an already-shared link");
	}

	@Test
	void markOpenedOnlyRecordsTheFirstVisit() throws InterruptedException {
		InvitedEmail invite = new InvitedEmail("friend@gmail.com", "admin");

		invite.markOpened();
		var firstOpen = invite.getOpenedAt();
		Thread.sleep(5);
		invite.markOpened(); // a second click on the same link

		assertEquals(firstOpen, invite.getOpenedAt(), "the open time shouldn't move on a later re-visit");
	}

	@Test
	void unopenedInviteHasNoOpenedAt() {
		InvitedEmail invite = new InvitedEmail("friend@gmail.com", "admin");

		assertTrue(invite.getOpenedAt() == null);
	}
}
