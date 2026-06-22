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
import org.springframework.web.cors.CorsUtils;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Gates {@code /api/**} behind a valid Redis session (Story 2.1, AC #1/#5). Registered for the
 * {@code /api/*} URL pattern only (see {@link SecurityConfig}), so non-API paths (actuator,
 * static, WebSocket handshake) are untouched.
 *
 * <p>Allowlist (no session required): CORS preflights and the pre-login auth endpoints.
 * Everything else under {@code /api} returns an RFC 9457 401 — written directly here because the
 * filter runs before Spring MVC's {@code GlobalExceptionHandler} (and is decoupled from Jackson).
 * Session validation is <b>fail-closed</b>: if the session store is unreachable, the request is
 * treated as unauthenticated rather than 500-ing.
 */
public class SessionAuthFilter extends OncePerRequestFilter {

	private final SessionStore sessions;

	public SessionAuthFilter(SessionStore sessions) {
		this.sessions = sessions;
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
			FilterChain chain) throws ServletException, IOException {

		// CORS preflights carry no credentials and must reach Spring's CORS handling, not 401.
		if (CorsUtils.isPreFlightRequest(request)) {
			chain.doFilter(request, response);
			return;
		}

		if (isAllowlisted(request) || isAuthenticated(request)) {
			chain.doFilter(request, response);
			return;
		}
		writeUnauthorized(request, response);
	}

	/** Fail-closed: any error reaching the session store counts as "not authenticated". */
	private boolean isAuthenticated(HttpServletRequest request) {
		try {
			return sessions.validate(SessionCookie.read(request));
		} catch (RuntimeException ex) {
			return false;
		}
	}

	/** Pre-login endpoints reachable without a session (path matched tolerant of a trailing slash). */
	private boolean isAllowlisted(HttpServletRequest request) {
		String path = normalize(request.getRequestURI());
		HttpMethod method = HttpMethod.valueOf(request.getMethod());
		return (HttpMethod.GET.equals(method) && "/api/auth/status".equals(path))
				|| (HttpMethod.POST.equals(method) && "/api/auth/login".equals(path))
				|| (HttpMethod.POST.equals(method) && "/api/auth/pin".equals(path));
	}

	/** Strip a single trailing slash (but keep root "/") so proxy normalization can't 401 a match. */
	private static String normalize(String path) {
		if (path.length() > 1 && path.endsWith("/")) {
			return path.substring(0, path.length() - 1);
		}
		return path;
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
