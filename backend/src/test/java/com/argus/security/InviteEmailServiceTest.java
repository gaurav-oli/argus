package com.argus.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/** The pure parts: link building and the "not configured" guard. The real Resend HTTP call is
 * verified live post-deploy (same convention as GoogleOAuthService's token exchange). */
class InviteEmailServiceTest {

	@Test
	void buildsTheInviteLinkUnderTheConfiguredAppUrl() {
		InviteEmailService service = new InviteEmailService(
				new ResendProperties("re_test", "onboarding@resend.dev"), "https://sh-dow.taila43287.ts.net");

		assertEquals("https://sh-dow.taila43287.ts.net/?invite=abc123", service.inviteLink("abc123"));
	}

	@Test
	void aTrailingSlashOnTheAppUrlDoesNotDoubleUp() {
		InviteEmailService service = new InviteEmailService(
				new ResendProperties("re_test", "onboarding@resend.dev"), "https://sh-dow.taila43287.ts.net/");

		assertEquals("https://sh-dow.taila43287.ts.net/?invite=abc123", service.inviteLink("abc123"));
	}

	@Test
	void sendingWithNoApiKeyConfiguredFailsClearlyRatherThanSilentlyNoOpping() {
		InviteEmailService service = new InviteEmailService(
				new ResendProperties("", "onboarding@resend.dev"), "https://sh-dow.taila43287.ts.net");

		InviteEmailException ex = assertThrows(InviteEmailException.class,
				() -> service.send("friend@gmail.com", "tok", "Admin"));
		assertEquals("Email sending isn't set up (no Resend API key configured)", ex.getMessage());
	}
}
