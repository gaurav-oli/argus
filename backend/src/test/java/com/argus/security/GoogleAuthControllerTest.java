package com.argus.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/** The Google Sign-In flow's provisioning/rejection logic, with GoogleOAuthService mocked out —
 * its own crypto correctness is {@link GoogleOAuthServiceTest}'s job. */
class GoogleAuthControllerTest {

	private final GoogleOAuthService google = mock(GoogleOAuthService.class);
	private final InvitedEmailRepository invited = mock(InvitedEmailRepository.class);
	private final AppUserRepository users = mock(AppUserRepository.class);
	private final SessionStore sessions = mock(SessionStore.class);
	private final SecurityProperties securityProperties = new SecurityProperties(java.time.Duration.ofMinutes(15), true);

	private GoogleAuthController controller(GoogleOAuthProperties props) {
		return new GoogleAuthController(google, props, invited, users, sessions, securityProperties);
	}

	private static final GoogleOAuthProperties CONFIGURED =
			new GoogleOAuthProperties("client-id", "secret", "https://example.ts.net/api/login/oauth2/code/google", "admin@example.com");

	private static HttpServletRequest requestWithStateCookie(String value) {
		HttpServletRequest req = mock(HttpServletRequest.class);
		when(req.getCookies()).thenReturn(value == null ? null : new Cookie[] {new Cookie(GoogleAuthController.STATE_COOKIE, value)});
		when(req.getHeader("User-Agent")).thenReturn("Mozilla/5.0 (Macintosh)");
		return req;
	}

	@Test
	void loginIsUnavailableWhenNoClientIsConfigured() {
		ResponseEntity<Void> res = controller(new GoogleOAuthProperties("", "", "http://x", "")).login();

		assertEquals(HttpStatus.SERVICE_UNAVAILABLE, res.getStatusCode());
	}

	@Test
	void loginRedirectsToGoogleAndSetsAStateCookie() {
		when(google.authorizationUrl(anyString())).thenReturn("https://accounts.google.com/o/oauth2/v2/auth?mock=1");

		ResponseEntity<Void> res = controller(CONFIGURED).login();

		assertEquals(HttpStatus.FOUND, res.getStatusCode());
		assertEquals("https://accounts.google.com/o/oauth2/v2/auth?mock=1", res.getHeaders().getFirst(HttpHeaders.LOCATION));
		assertTrue(res.getHeaders().get(HttpHeaders.SET_COOKIE).stream().anyMatch(c -> c.startsWith(GoogleAuthController.STATE_COOKIE + "=")));
	}

	@Test
	void callbackRejectsAMissingStateCookieWithoutCallingGoogleAtAll() {
		ResponseEntity<Void> res = controller(CONFIGURED).callback("a-code", "the-state", null, requestWithStateCookie(null));

		assertEquals(HttpStatus.FOUND, res.getStatusCode());
		assertEquals("/?auth=failed", res.getHeaders().getFirst(HttpHeaders.LOCATION));
		verify(google, never()).exchangeCodeForIdToken(anyString());
	}

	@Test
	void callbackRejectsAStateThatDoesNotMatchTheCookie() {
		ResponseEntity<Void> res = controller(CONFIGURED).callback("a-code", "wrong-state", null, requestWithStateCookie("expected-state"));

		assertEquals("/?auth=failed", res.getHeaders().getFirst(HttpHeaders.LOCATION));
		verify(google, never()).exchangeCodeForIdToken(anyString());
	}

	@Test
	void callbackRejectsWhenGoogleItselfReportedAnError() {
		ResponseEntity<Void> res = controller(CONFIGURED).callback(null, "s", "access_denied", requestWithStateCookie("s"));

		assertEquals("/?auth=failed", res.getHeaders().getFirst(HttpHeaders.LOCATION));
	}

	@Test
	void callbackRejectsAnUnverifiedEmailWithoutProvisioningAnything() {
		when(google.exchangeCodeForIdToken("code")).thenReturn("id-token");
		when(google.verify("id-token")).thenReturn(new GoogleIdentity("sub-1", "new@gmail.com", false, "New Person", null));

		ResponseEntity<Void> res = controller(CONFIGURED).callback("code", "s", null, requestWithStateCookie("s"));

		assertEquals("/?auth=failed", res.getHeaders().getFirst(HttpHeaders.LOCATION));
		verify(users, never()).save(any());
	}

	@Test
	void callbackRejectsAnEmailNotOnTheInviteListAndCreatesNoAccount() {
		when(google.exchangeCodeForIdToken("code")).thenReturn("id-token");
		when(google.verify("id-token")).thenReturn(new GoogleIdentity("sub-1", "stranger@gmail.com", true, "Stranger", null));
		when(users.findByGoogleSub("sub-1")).thenReturn(Optional.empty());
		when(invited.findById("stranger@gmail.com")).thenReturn(Optional.empty());

		ResponseEntity<Void> res = controller(CONFIGURED).callback("code", "s", null, requestWithStateCookie("s"));

		assertEquals("/?auth=not_invited", res.getHeaders().getFirst(HttpHeaders.LOCATION));
		verify(users, never()).save(any());
		verify(sessions, never()).create(anyString(), any());
	}

	@Test
	void callbackProvisionsANewInvitedUserAndStartsASession() {
		when(google.exchangeCodeForIdToken("code")).thenReturn("id-token");
		when(google.verify("id-token")).thenReturn(new GoogleIdentity("sub-2", "friend@gmail.com", true, "A Friend", "https://pic"));
		when(users.findByGoogleSub("sub-2")).thenReturn(Optional.empty());
		when(invited.findById("friend@gmail.com")).thenReturn(Optional.of(new InvitedEmail("friend@gmail.com", "admin")));
		when(users.save(any(AppUser.class))).thenAnswer(inv -> inv.getArgument(0));
		when(sessions.create(eq("Mac"), any())).thenReturn("new-session-id");
		when(sessions.cookieMaxAge()).thenReturn(Optional.empty());

		ResponseEntity<Void> res = controller(CONFIGURED).callback("code", "s", null, requestWithStateCookie("s"));

		assertEquals(HttpStatus.FOUND, res.getStatusCode());
		assertEquals("/", res.getHeaders().getFirst(HttpHeaders.LOCATION));
		assertTrue(res.getHeaders().get(HttpHeaders.SET_COOKIE).stream().anyMatch(c -> c.startsWith(SessionCookie.NAME + "=new-session-id")));

		org.mockito.ArgumentCaptor<AppUser> captor = org.mockito.ArgumentCaptor.forClass(AppUser.class);
		verify(users).save(captor.capture());
		assertEquals("friend@gmail.com", captor.getValue().getEmail());
		assertEquals(1, captor.getValue().getLoginCount());
	}

	@Test
	void callbackPromotesOnlyTheConfiguredAdminEmailToAdminOnFirstCreation() {
		when(google.exchangeCodeForIdToken("code")).thenReturn("id-token");
		when(google.verify("id-token")).thenReturn(new GoogleIdentity("sub-admin", "Admin@Example.com", true, "The Admin", null));
		when(users.findByGoogleSub("sub-admin")).thenReturn(Optional.empty());
		when(invited.findById("admin@example.com")).thenReturn(Optional.of(new InvitedEmail("admin@example.com", "system")));
		when(users.save(any(AppUser.class))).thenAnswer(inv -> inv.getArgument(0));
		when(sessions.create(anyString(), any())).thenReturn("sid");
		when(sessions.cookieMaxAge()).thenReturn(Optional.empty());

		controller(CONFIGURED).callback("code", "s", null, requestWithStateCookie("s"));

		org.mockito.ArgumentCaptor<AppUser> captor = org.mockito.ArgumentCaptor.forClass(AppUser.class);
		verify(users).save(captor.capture());
		assertTrue(captor.getValue().isAdmin(), "the configured admin email, case-insensitively, must be promoted on creation");
	}

	@Test
	void callbackUpdatesAnExistingAccountsProfileWithoutRecheckingTheInviteList() {
		AppUser existing = new AppUser("sub-3", "old@gmail.com", "Old Name", null, false);
		when(google.exchangeCodeForIdToken("code")).thenReturn("id-token");
		when(google.verify("id-token")).thenReturn(new GoogleIdentity("sub-3", "old@gmail.com", true, "New Display Name", "https://new-pic"));
		when(users.findByGoogleSub("sub-3")).thenReturn(Optional.of(existing));
		when(users.save(any(AppUser.class))).thenAnswer(inv -> inv.getArgument(0));
		when(sessions.create(anyString(), any())).thenReturn("sid");
		when(sessions.cookieMaxAge()).thenReturn(Optional.empty());

		controller(CONFIGURED).callback("code", "s", null, requestWithStateCookie("s"));

		assertEquals("New Display Name", existing.getName());
		assertEquals(1, existing.getLoginCount());
		verify(invited, never()).findById(anyString());
	}

	@Test
	void callbackFailsSafelyWhenGoogleVerificationThrows() {
		when(google.exchangeCodeForIdToken("code")).thenThrow(new GoogleAuthException("token exchange boom"));

		ResponseEntity<Void> res = controller(CONFIGURED).callback("code", "s", null, requestWithStateCookie("s"));

		assertEquals("/?auth=failed", res.getHeaders().getFirst(HttpHeaders.LOCATION));
	}
}
