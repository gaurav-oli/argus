package com.argus.security;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.argus.TestcontainersConfiguration;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/** S-A3: ops/settings mutators require admin; ordinary invitees get 403. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class AdminMutatorGateIntegrationTest {

	@Autowired
	MockMvc mockMvc;

	@Autowired
	AppUserRepository appUsers;

	@Autowired
	SessionStore sessions;

	@Test
	void nonAdminCannotMutateOpsOrGlobalSettings() throws Exception {
		Cookie user = TestUserSessions.loginAsNewUser(appUsers, sessions);

		mockMvc.perform(post("/api/ops/backup/trigger").cookie(user)).andExpect(status().isForbidden());
		mockMvc.perform(post("/api/ops/cleanup/preview").cookie(user)).andExpect(status().isForbidden());
		mockMvc.perform(post("/api/ops/cleanup/run").cookie(user)).andExpect(status().isForbidden());
		mockMvc.perform(post("/api/ops/logic-review/run").cookie(user)).andExpect(status().isForbidden());
		mockMvc.perform(post("/api/recommendations/tuning/recompute").cookie(user))
				.andExpect(status().isForbidden());
		mockMvc.perform(post("/api/recommendations/graduation/resume").cookie(user))
				.andExpect(status().isForbidden());
		mockMvc.perform(put("/api/settings/session-timeout").cookie(user)
						.contentType(MediaType.APPLICATION_JSON).content("{\"seconds\":1800}"))
				.andExpect(status().isForbidden());
		mockMvc.perform(put("/api/settings/demo-mode").cookie(user).contentType(MediaType.APPLICATION_JSON)
						.content("{\"demoMode\":true}"))
				.andExpect(status().isForbidden());
	}

	@Test
	void adminCanCallBackupTriggerAndSettingsWrites() throws Exception {
		Cookie admin = TestUserSessions.loginAsAdmin(appUsers, sessions);

		mockMvc.perform(post("/api/ops/backup/trigger").cookie(admin)).andExpect(status().isOk());
		mockMvc.perform(put("/api/settings/session-timeout").cookie(admin)
						.contentType(MediaType.APPLICATION_JSON).content("{\"seconds\":1800}"))
				.andExpect(status().isNoContent());
		mockMvc.perform(put("/api/settings/demo-mode").cookie(admin).contentType(MediaType.APPLICATION_JSON)
						.content("{\"demoMode\":false}"))
				.andExpect(status().isOk());
	}
}
