package com.argus.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;
import org.springframework.mail.javamail.JavaMailSender;

/** The pure parts: link building and the "not configured" guard. The real Gmail SMTP send is
 * verified live post-deploy (same convention as GoogleOAuthService's token exchange). */
class InviteEmailServiceTest {

	private final JavaMailSender mailSender = mock(JavaMailSender.class);

	@Test
	void buildsTheInviteLinkUnderTheConfiguredAppUrl() {
		InviteEmailService service =
				new InviteEmailService(mailSender, "admin@gmail.com", "https://sh-dow.taila43287.ts.net");

		assertEquals("https://sh-dow.taila43287.ts.net/?invite=abc123", service.inviteLink("abc123"));
	}

	@Test
	void aTrailingSlashOnTheAppUrlDoesNotDoubleUp() {
		InviteEmailService service =
				new InviteEmailService(mailSender, "admin@gmail.com", "https://sh-dow.taila43287.ts.net/");

		assertEquals("https://sh-dow.taila43287.ts.net/?invite=abc123", service.inviteLink("abc123"));
	}

	@Test
	void sendingWithNoGmailAddressConfiguredFailsClearlyRatherThanSilentlyNoOpping() {
		InviteEmailService service = new InviteEmailService(mailSender, "", "https://sh-dow.taila43287.ts.net");

		InviteEmailException ex = assertThrows(InviteEmailException.class,
				() -> service.send("friend@gmail.com", "tok", "Admin"));
		assertEquals("Email sending isn't set up (no Gmail address/app password configured)", ex.getMessage());
	}
}
