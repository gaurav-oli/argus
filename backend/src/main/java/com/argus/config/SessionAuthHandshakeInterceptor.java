package com.argus.config;

import com.argus.security.SessionCookie;
import com.argus.security.SessionStore;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

/**
 * Rejects the {@code /ws} upgrade unless the request carries a live {@code ARGUS_SESSION} with a
 * signed-in {@code userId} (S-A1 / platform improvement plan C2). Funnel exposes {@code /ws}
 * publicly; invite-only REST is useless if an anonymous STOMP client can still listen.
 *
 * <p>Legacy sessions without {@code userId} (pre-Google) are also rejected — they must not open a
 * socket that could later SUBSCRIBE to broker destinations.
 */
public final class SessionAuthHandshakeInterceptor implements HandshakeInterceptor {

	private final SessionStore sessions;

	public SessionAuthHandshakeInterceptor(SessionStore sessions) {
		this.sessions = sessions;
	}

	@Override
	public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
			WebSocketHandler wsHandler, Map<String, Object> attributes) {
		if (!(request instanceof ServletServerHttpRequest servletRequest)) {
			return false;
		}
		HttpServletRequest http = servletRequest.getServletRequest();
		return sessions.userId(SessionCookie.read(http)).isPresent();
	}

	@Override
	public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
			WebSocketHandler wsHandler, Exception exception) {
		// no-op
	}
}
