package com.argus.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.argus.email.EmailSendException;
import com.argus.email.EmailSender;
import org.junit.jupiter.api.Test;

/** The invite email's own concerns (the link, wrapping a send failure) — the actual SMTP mechanics
 * are {@link EmailSender}'s job, covered there. */
class InviteEmailServiceTest {

	private final EmailSender sender = mock(EmailSender.class);

	@Test
	void buildsTheInviteLinkUnderTheConfiguredAppUrl() {
		InviteEmailService service = new InviteEmailService(sender, "https://sh-dow.taila43287.ts.net");

		assertEquals("https://sh-dow.taila43287.ts.net/?invite=abc123", service.inviteLink("abc123"));
	}

	@Test
	void aTrailingSlashOnTheAppUrlDoesNotDoubleUp() {
		InviteEmailService service = new InviteEmailService(sender, "https://sh-dow.taila43287.ts.net/");

		assertEquals("https://sh-dow.taila43287.ts.net/?invite=abc123", service.inviteLink("abc123"));
	}

	@Test
	void aFailedSendIsWrappedAsAnInviteEmailException() {
		InviteEmailService service = new InviteEmailService(sender, "https://sh-dow.taila43287.ts.net");
		doThrow(new EmailSendException("Gmail rejected the email")).when(sender).send(any(), any(), any());

		InviteEmailException ex = assertThrows(InviteEmailException.class,
				() -> service.send("friend@gmail.com", "tok", "Admin"));
		assertEquals("Gmail rejected the email", ex.getMessage());
	}

	@Test
	void sendingCallsTheSenderWithTheRightRecipientSubjectAndLink() {
		InviteEmailService service = new InviteEmailService(sender, "https://sh-dow.taila43287.ts.net");

		service.send("friend@gmail.com", "tok123", "Admin");

		verify(sender).send(org.mockito.ArgumentMatchers.eq("friend@gmail.com"),
				org.mockito.ArgumentMatchers.eq("Admin invited you to Argus"),
				org.mockito.ArgumentMatchers.argThat(
						html -> html != null && html.contains("https://sh-dow.taila43287.ts.net/?invite=tok123")));
	}
}
