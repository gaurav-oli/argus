package com.argus.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Gates {@code /api/**} behind a valid Redis session (Story 2.1, AC #1/#5). Registered for the
 * {@code /api/*} URL pattern only (see {@link SecurityConfig}), so non-API paths (actuator,
 * static, WebSocket handshake) are untouched.
 *
 * <p>Allowlist (no session required): the pre-login auth endpoints. Everything else under
 * {@code /api} returns an RFC 9457 401 — written directly here because the filter runs before
 * Spring MVC's {@code GlobalExceptionHandler} (and is decoupled from the Jackson version).
 */
public class SessionAuthFilter extends OncePerRequestFilter {

	private final SessionStore sessions;

	public SessionAuthFilter(SessionStore sessions) {
		this.sessions = sessions;
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
			FilterChain chain) throws ServletException, IOException {

		if (isAllowlisted(request) || sessions.validate(SessionCookie.read(request))) {
			chain.doFilter(request, response);
			return;
		}
		writeUnauthorized(request, response);
	}

	/** Pre-login endpoints reachable without a session. */
	private boolean isAllowlisted(HttpServletRequest request) {
		String path = request.getRequestURI();
		HttpMethod method = HttpMethod.valueOf(request.getMethod());
		return (HttpMethod.GET.equals(method) && "/api/auth/status".equals(path))
				|| (HttpMethod.POST.equals(method) && "/api/auth/login".equals(path))
				|| (HttpMethod.POST.equals(method) && "/api/auth/pin".equals(path));
	}

	private void writeUnauthorized(HttpServletRequest request, HttpServletResponse response)
			throws IOException {
		String body = """
				{"type":"https://argus.local/problems/unauthorized",\
				"title":"Unauthorized","status":401,\
				"detail":"Authentication required","instance":"%s"}\
				""".formatted(jsonEscape(request.getRequestURI()));

		response.setStatus(HttpStatus.UNAUTHORIZED.value());
		response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
		response.setCharacterEncoding(StandardCharsets.UTF_8.name());
		response.getWriter().write(body);
	}

	private static String jsonEscape(String value) {
		return value.replace("\\", "\\\\").replace("\"", "\\\"");
	}
}
