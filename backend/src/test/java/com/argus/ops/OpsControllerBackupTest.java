package com.argus.ops;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The "Back Up Now" endpoints, session-gated under {@code /api/ops/backup}. The test profile has no
 * {@code argus.backup.dir} configured, so these exercise the disabled-feature guard rather than a
 * real pg_dump (not installed on the dev machine — the actual dump path is verified live post-deploy,
 * against the real image which has the binary).
 */
@SpringBootTest
@ActiveProfiles("dev")
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class OpsControllerBackupTest {

	@Autowired
	MockMvc mockMvc;

	@Autowired
	com.argus.security.AppCredentialRepository credentials;

	@BeforeEach
	void clean() {
		credentials.deleteAll(); // shared test DB — start without a PIN so setup returns 201
	}

	private Cookie login() throws Exception {
		mockMvc.perform(post("/api/auth/pin").contentType(MediaType.APPLICATION_JSON).content("{\"pin\":\"1234\"}"))
				.andExpect(status().isCreated());
		return mockMvc.perform(post("/api/auth/login")
						.contentType(MediaType.APPLICATION_JSON).content("{\"pin\":\"1234\"}"))
				.andExpect(status().isOk())
				.andReturn().getResponse().getCookie("ARGUS_SESSION");
	}

	@Test
	void backupEndpointsAreSessionGated() throws Exception {
		mockMvc.perform(get("/api/ops/backup")).andExpect(status().isUnauthorized());
		mockMvc.perform(post("/api/ops/backup/trigger")).andExpect(status().isUnauthorized());
	}

	@Test
	void statusReflectsTheDisabledFeatureWhenNoBackupDirIsConfigured() throws Exception {
		Cookie session = login();

		mockMvc.perform(get("/api/ops/backup").cookie(session))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status.enabled").value(false))
				.andExpect(jsonPath("$.trigger.state").value("IDLE"));
	}

	@Test
	void triggerFailsFastAndReportsWhyWhenBackupsAreNotConfigured() throws Exception {
		Cookie session = login();

		mockMvc.perform(post("/api/ops/backup/trigger").cookie(session))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.trigger.state").value("FAILED"))
				.andExpect(jsonPath("$.trigger.message").isNotEmpty());
	}
}
