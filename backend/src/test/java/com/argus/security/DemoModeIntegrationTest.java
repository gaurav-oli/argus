package com.argus.security;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.argus.TestcontainersConfiguration;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/** Demo Mode (session-gated under {@code /api/settings/demo-mode}) against real Postgres — a PUT
 * should persist across a subsequent GET, sharing the app_settings singleton row. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class DemoModeIntegrationTest {

	@Autowired
	MockMvc mockMvc;

	@Autowired
	AppUserRepository appUsers;

	@Autowired
	SessionStore sessions;

	@Autowired
	AppSettingsRepository settings;

	@Autowired
	DemoModeService demoMode;

	@BeforeEach
	void clean() {
		settings.deleteAll();
		// DemoModeService caches demoMode in memory (@PostConstruct load()) — the Spring context (and
		// so the bean) is reused across tests in this class, so wiping the DB row alone leaves a
		// previous test's cached value stale. Re-load to resync, same effect a real restart would have.
		demoMode.load();
	}

	private Cookie login() {
		return TestUserSessions.loginAsAdmin(appUsers, sessions);
	}

	@Test
	void unauthenticatedRequestIsRejected() throws Exception {
		mockMvc.perform(get("/api/settings/demo-mode")).andExpect(status().isUnauthorized());
	}

	@Test
	void getReturnsOffByDefault() throws Exception {
		Cookie session = login();

		mockMvc.perform(get("/api/settings/demo-mode").cookie(session))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.demoMode").value(false));
	}

	@Test
	void putPersistsAcrossASubsequentGet() throws Exception {
		Cookie session = login();

		mockMvc.perform(put("/api/settings/demo-mode").cookie(session).contentType(MediaType.APPLICATION_JSON)
						.content("{\"demoMode\":true}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.demoMode").value(true));

		mockMvc.perform(get("/api/settings/demo-mode").cookie(session))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.demoMode").value(true));
	}
}
