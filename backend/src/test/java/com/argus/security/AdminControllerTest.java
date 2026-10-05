package com.argus.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.argus.common.ForbiddenException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Admin-only invite management (Multi-user Phase 1 follow-up): any real email can be let in, and
 * only an admin can do it. */
class AdminControllerTest {

	private final CurrentUserService currentUser = mock(CurrentUserService.class);
	private final AppUserRepository users = mock(AppUserRepository.class);
	private final UserActivityService activity = mock(UserActivityService.class);
	private final InvitedEmailRepository invites = mock(InvitedEmailRepository.class);
	private final InviteEmailService inviteEmail = mock(InviteEmailService.class);
	private final AdminController controller = new AdminController(currentUser, users, activity, invites, inviteEmail);

	private static final AppUser ADMIN = new AppUser("sub-admin", "admin@example.com", "Admin", null, true);
	private final HttpServletRequest request = mock(HttpServletRequest.class);

	@Test
	void invitingRequiresAdmin() {
		when(currentUser.requireAdmin(request)).thenThrow(new ForbiddenException("Admin only"));

		assertThrows(ForbiddenException.class,
				() -> controller.invite(new AdminController.InviteRequest("friend@gmail.com"), request));
		verify(invites, never()).save(any());
	}

	@Test
	void invitingANewEmailSavesItNormalizedAndMarksItNotYetJoined() {
		when(currentUser.requireAdmin(request)).thenReturn(ADMIN);
		when(invites.findById("friend@gmail.com")).thenReturn(Optional.empty());
		when(invites.save(any())).thenAnswer(inv -> inv.getArgument(0));
		when(users.findByEmailIgnoreCase("friend@gmail.com")).thenReturn(Optional.empty());

		AdminController.InviteView view =
				controller.invite(new AdminController.InviteRequest("  Friend@Gmail.com  "), request);

		assertEquals("friend@gmail.com", view.email());
		assertTrue(!view.joined());
	}

	@Test
	void invitingAnAlreadyInvitedEmailIsANoOpNotADuplicate() {
		when(currentUser.requireAdmin(request)).thenReturn(ADMIN);
		InvitedEmail existing = new InvitedEmail("friend@gmail.com", "admin@example.com");
		when(invites.findById("friend@gmail.com")).thenReturn(Optional.of(existing));
		when(users.findByEmailIgnoreCase("friend@gmail.com")).thenReturn(Optional.empty());

		controller.invite(new AdminController.InviteRequest("friend@gmail.com"), request);

		verify(invites, never()).save(any());
	}

	@Test
	void invitingWithNoEmailIsRejected() {
		when(currentUser.requireAdmin(request)).thenReturn(ADMIN);

		assertThrows(org.springframework.web.server.ResponseStatusException.class,
				() -> controller.invite(new AdminController.InviteRequest(""), request));
	}

	@Test
	void listingInvitesMarksWhoHasActuallySignedInAlready() {
		when(currentUser.requireAdmin(request)).thenReturn(ADMIN);
		InvitedEmail joined = new InvitedEmail("joined@gmail.com", "admin");
		InvitedEmail pending = new InvitedEmail("pending@gmail.com", "admin");
		when(invites.findAllByOrderByInvitedAtAsc()).thenReturn(List.of(joined, pending));
		when(users.findByEmailIgnoreCase("joined@gmail.com"))
				.thenReturn(Optional.of(new AppUser("s", "joined@gmail.com", "Joined", null, false)));
		when(users.findByEmailIgnoreCase("pending@gmail.com")).thenReturn(Optional.empty());

		List<AdminController.InviteView> views = controller.invites(request);

		assertTrue(views.get(0).joined());
		assertTrue(!views.get(1).joined());
	}

	@Test
	void sendingRequiresAdmin() {
		when(currentUser.requireAdmin(request)).thenThrow(new ForbiddenException("Admin only"));

		assertThrows(ForbiddenException.class,
				() -> controller.sendInvite(new AdminController.InviteRequest("friend@gmail.com"), request));
		verify(inviteEmail, never()).send(any(), any(), any());
	}

	@Test
	void sendingEmailsTheInvitesOwnTokenAndStampsWhenItWasSent() {
		when(currentUser.requireAdmin(request)).thenReturn(ADMIN);
		when(invites.findById("friend@gmail.com")).thenReturn(Optional.empty());
		when(invites.save(any())).thenAnswer(inv -> inv.getArgument(0));
		when(users.findByEmailIgnoreCase("friend@gmail.com")).thenReturn(Optional.empty());

		AdminController.InviteView view =
				controller.sendInvite(new AdminController.InviteRequest("friend@gmail.com"), request);

		org.mockito.ArgumentCaptor<String> tokenCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
		verify(inviteEmail).send(org.mockito.ArgumentMatchers.eq("friend@gmail.com"), tokenCaptor.capture(),
				org.mockito.ArgumentMatchers.eq("Admin"));
		assertTrue(!tokenCaptor.getValue().isBlank(), "a real token should be generated and sent");
		assertTrue(view.emailSentAt() != null, "sending should stamp emailSentAt");
	}

	@Test
	void aFailedSendSurfacesAsServiceUnavailableNotA500() {
		when(currentUser.requireAdmin(request)).thenReturn(ADMIN);
		when(invites.findById("friend@gmail.com")).thenReturn(Optional.empty());
		when(invites.save(any())).thenAnswer(inv -> inv.getArgument(0));
		org.mockito.Mockito.doThrow(new InviteEmailException("Resend rejected the email"))
				.when(inviteEmail).send(any(), any(), any());

		org.springframework.web.server.ResponseStatusException ex = assertThrows(
				org.springframework.web.server.ResponseStatusException.class,
				() -> controller.sendInvite(new AdminController.InviteRequest("friend@gmail.com"), request));
		assertEquals(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE, ex.getStatusCode());
	}
}
