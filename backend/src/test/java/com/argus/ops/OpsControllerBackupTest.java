package com.argus.ops;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.argus.TestcontainersConfiguration;
import com.argus.security.TestUserSessions;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
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
	com.argus.security.AppUserRepository appUsers;

	@Autowired
	com.argus.security.SessionStore sessions;

	private Cookie login() {
		return TestUserSessions.loginAsAdmin(appUsers, sessions);
	}

	private Cookie loginAsFriend() {
		return TestUserSessions.loginAsNewUser(appUsers, sessions);
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

	@Test
	void nonAdminCannotTriggerBackup() throws Exception {
		Cookie friend = loginAsFriend();
		mockMvc.perform(post("/api/ops/backup/trigger").cookie(friend)).andExpect(status().isForbidden());
	}
}
