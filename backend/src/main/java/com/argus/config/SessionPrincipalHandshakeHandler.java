package com.argus.config;

import com.argus.security.SessionCookie;
import com.argus.security.SessionStore;
import jakarta.servlet.http.HttpServletRequest;
import java.security.Principal;
import java.util.Map;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.support.DefaultHandshakeHandler;

/**
 * Resolves the signed-in person as the WebSocket connection's {@link Principal}, from the same
 * {@code ARGUS_SESSION} cookie the REST API uses (this app has no Spring Security, so there is no
 * Principal to inherit) — browsers send cookies on a WebSocket handshake exactly like any other
 * same-origin request. This is what lets a per-user STOMP destination ({@code /user/queue/...})
 * target exactly one person's own open connection(s), never anyone else's (Phase 2, multi-user).
 *
 * <p>Unauthenticated upgrades are rejected earlier by {@link SessionAuthHandshakeInterceptor}
 * (S-A1). This handler therefore expects a session with a {@code userId}; if somehow missing, it
 * returns null and personal {@code convertAndSendToUser} delivery fails closed.
 */
public class SessionPrincipalHandshakeHandler extends DefaultHandshakeHandler {

	private final SessionStore sessions;

	public SessionPrincipalHandshakeHandler(SessionStore sessions) {
		this.sessions = sessions;
	}

	@Override
	protected Principal determineUser(ServerHttpRequest request, WebSocketHandler wsHandler,
			Map<String, Object> attributes) {
		if (!(request instanceof ServletServerHttpRequest servletRequest)) {
			return null;
		}
		HttpServletRequest httpRequest = servletRequest.getServletRequest();
		String sessionId = SessionCookie.read(httpRequest);
		return sessions.userId(sessionId).<Principal>map(id -> (Principal) (id::toString)).orElse(null);
	}
}
