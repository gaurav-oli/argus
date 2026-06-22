package com.argus.security;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.argus.TestcontainersConfiguration;
import jakarta.servlet.http.Cookie;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * End-to-end auth flow (Story 2.1) against real Postgres + Redis (Testcontainers):
 * status → setup → login → authorized call → logout → 401, plus wrong-PIN, duplicate-setup,
 * invalid-format, and the {@code /api/**} gate.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class AuthFlowIntegrationTest {

	@Autowired
	MockMvc mockMvc;

	@Autowired
	AppCredentialRepository credentials;

	@Autowired
	StringRedisTemplate redis;

	@BeforeEach
	void reset() {
		credentials.deleteAll();
		Set<String> sessionKeys = redis.keys(SessionStore.KEY_PREFIX + "*");
		if (sessionKeys != null && !sessionKeys.isEmpty()) {
			redis.delete(sessionKeys);
		}
	}

	private static String pinBody(String pin) {
		return "{\"pin\":\"" + pin + "\"}";
	}

	@Test
	void fullHappyPath() throws Exception {
		// 1. Fresh install: no PIN, not authenticated (status is allowlisted).
		mockMvc.perform(get("/api/auth/status"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.pinSet").value(false))
				.andExpect(jsonPath("$.authenticated").value(false));

		// 2. First-launch PIN setup.
		mockMvc.perform(post("/api/auth/pin").contentType(MediaType.APPLICATION_JSON).content(pinBody("1234")))
				.andExpect(status().isCreated());

		// 3. PIN now set, still unauthenticated.
		mockMvc.perform(get("/api/auth/status"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.pinSet").value(true))
				.andExpect(jsonPath("$.authenticated").value(false));

		// 4. Wrong PIN → 401, no session cookie.
		mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(pinBody("9999")))
				.andExpect(status().isUnauthorized())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
				.andExpect(cookie().doesNotExist(SessionCookie.NAME));

		// 5. Correct PIN → 200 + HttpOnly session cookie.
		MvcResult login = mockMvc.perform(post("/api/auth/login")
						.contentType(MediaType.APPLICATION_JSON).content(pinBody("1234")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.authenticated").value(true))
				.andExpect(cookie().exists(SessionCookie.NAME))
				.andExpect(cookie().httpOnly(SessionCookie.NAME, true))
				.andReturn();
		Cookie session = login.getResponse().getCookie(SessionCookie.NAME);
		assertNotNull(session);
		assertTrue(session.getValue().length() > 20, "session id should be a long random token");

		// 6. Gated endpoint without the cookie → 401.
		mockMvc.perform(get("/api/system-info"))
				.andExpect(status().isUnauthorized())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));

		// 7. Gated endpoint WITH the cookie → 200.
		mockMvc.perform(get("/api/system-info").cookie(session))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.name").value("argus"));

		// 8. Status with the cookie → authenticated.
		mockMvc.perform(get("/api/auth/status").cookie(session))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.authenticated").value(true));

		// 9. Logout destroys the session.
		mockMvc.perform(post("/api/auth/logout").cookie(session))
				.andExpect(status().isNoContent());

		// 10. The old cookie is now worthless → 401.
		mockMvc.perform(get("/api/system-info").cookie(session))
				.andExpect(status().isUnauthorized());
	}

	@Test
	void duplicateSetupReturnsConflict() throws Exception {
		mockMvc.perform(post("/api/auth/pin").contentType(MediaType.APPLICATION_JSON).content(pinBody("1234")))
				.andExpect(status().isCreated());
		mockMvc.perform(post("/api/auth/pin").contentType(MediaType.APPLICATION_JSON).content(pinBody("5678")))
				.andExpect(status().isConflict())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
				.andExpect(jsonPath("$.title").value("Conflict"));
	}

	@Test
	void invalidPinFormatReturnsBadRequest() throws Exception {
		mockMvc.perform(post("/api/auth/pin").contentType(MediaType.APPLICATION_JSON).content(pinBody("12")))
				.andExpect(status().isBadRequest());
		// And no credential was created.
		assertNull(credentials.findSingleton().orElse(null));
	}

	@Test
	void unauthenticatedApiCallIsRejected() throws Exception {
		mockMvc.perform(get("/api/system-info"))
				.andExpect(status().isUnauthorized())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
				.andExpect(jsonPath("$.status").value(401));
	}
}
