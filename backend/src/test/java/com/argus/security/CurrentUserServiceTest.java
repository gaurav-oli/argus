package com.argus.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.argus.common.ForbiddenException;
import com.argus.common.UnauthorizedException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class CurrentUserServiceTest {

	private final SessionStore sessions = mock(SessionStore.class);
	private final AppUserRepository users = mock(AppUserRepository.class);
	private final CurrentUserService service = new CurrentUserService(sessions, users);

	private static HttpServletRequest requestWithSessionCookie(String value) {
		HttpServletRequest req = mock(HttpServletRequest.class);
		when(req.getCookies()).thenReturn(new Cookie[] {new Cookie(SessionCookie.NAME, value)});
		return req;
	}

	@Test
	void resolvesTheUserBehindALiveSession() {
		HttpServletRequest req = requestWithSessionCookie("session-abc");
		AppUser user = new AppUser("sub", "a@b.com", "A", null, false);
		when(sessions.userId("session-abc")).thenReturn(Optional.of(5L));
		when(users.findById(5L)).thenReturn(Optional.of(user));

		assertEquals(user, service.resolve(req).orElseThrow());
	}

	@Test
	void resolvesEmptyWithNoCookieAtAll() {
		HttpServletRequest req = mock(HttpServletRequest.class);
		when(req.getCookies()).thenReturn(null);

		assertTrue(service.resolve(req).isEmpty());
	}

	@Test
	void requireThrowsUnauthorizedWithNoSession() {
		HttpServletRequest req = mock(HttpServletRequest.class);
		when(req.getCookies()).thenReturn(null);

		assertThrows(UnauthorizedException.class, () -> service.require(req));
	}

	@Test
	void requireAdminThrowsForbiddenForASignedInNonAdmin() {
		HttpServletRequest req = requestWithSessionCookie("session-abc");
		AppUser user = new AppUser("sub", "a@b.com", "A", null, false);
		when(sessions.userId("session-abc")).thenReturn(Optional.of(5L));
		when(users.findById(5L)).thenReturn(Optional.of(user));

		assertThrows(ForbiddenException.class, () -> service.requireAdmin(req));
	}

	@Test
	void requireAdminSucceedsForAnAdmin() {
		HttpServletRequest req = requestWithSessionCookie("session-abc");
		AppUser admin = new AppUser("sub", "a@b.com", "A", null, true);
		when(sessions.userId("session-abc")).thenReturn(Optional.of(5L));
		when(users.findById(5L)).thenReturn(Optional.of(admin));

		assertEquals(admin, service.requireAdmin(req));
	}
}
