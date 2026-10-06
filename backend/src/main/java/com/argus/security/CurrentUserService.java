package com.argus.security;

import com.argus.common.ForbiddenException;
import com.argus.common.UnauthorizedException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Optional;
import org.springframework.stereotype.Service;

/** Resolves the Google-signed-in {@link AppUser} behind the current request's session, if any. */
@Service
public class CurrentUserService {

	private final SessionStore sessions;
	private final AppUserRepository users;

	public CurrentUserService(SessionStore sessions, AppUserRepository users) {
		this.sessions = sessions;
		this.users = users;
	}

	public Optional<AppUser> resolve(HttpServletRequest request) {
		// A revoked account is treated as signed out even if a session somehow outlived the revoke.
		return sessions.userId(SessionCookie.read(request)).flatMap(users::findById).filter(u -> !u.isRevoked());
	}

	/** For an endpoint that cannot run without a real user — should be unreachable given
	 * {@link SessionAuthFilter} already gates {@code /api/**}, but fails loudly rather than
	 * silently if it ever is. */
	public AppUser require(HttpServletRequest request) {
		return resolve(request).orElseThrow(() -> new UnauthorizedException("No signed-in user"));
	}

	/** For an admin-only endpoint. */
	public AppUser requireAdmin(HttpServletRequest request) {
		AppUser user = require(request);
		if (!user.isAdmin()) {
			throw new ForbiddenException("Admin only");
		}
		return user;
	}
}
