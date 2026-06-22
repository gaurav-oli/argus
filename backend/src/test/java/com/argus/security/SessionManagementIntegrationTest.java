package com.argus.security;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.argus.TestcontainersConfiguration;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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

/** Active-sessions list + remote kill (FR-39 / Story 2.7). */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class SessionManagementIntegrationTest {

	@Autowired
	MockMvc mockMvc;

	@Autowired
	AppCredentialRepository pinCredentials;

	@Autowired
	StringRedisTemplate redis;

	@Autowired
	SessionStore sessionStore;

	private final ObjectMapper json = new ObjectMapper();

	@BeforeEach
	void reset() {
		pinCredentials.deleteAll();
		Set<String> keys = redis.keys("argus:*");
		if (keys != null && !keys.isEmpty()) {
			redis.delete(keys);
		}
	}

	private Cookie login(String userAgent) throws Exception {
		return mockMvc.perform(post("/api/auth/login")
						.header("User-Agent", userAgent)
						.contentType(MediaType.APPLICATION_JSON).content("{\"pin\":\"1234\"}"))
				.andExpect(status().isOk())
				.andReturn().getResponse().getCookie("ARGUS_SESSION");
	}

	@Test
	void listAndRemotelyKillAnotherSession() throws Exception {
		mockMvc.perform(post("/api/auth/pin").contentType(MediaType.APPLICATION_JSON).content("{\"pin\":\"1234\"}"))
				.andExpect(status().isCreated());

		Cookie phone = login("Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) Safari");
		Cookie laptop = login("Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15) Safari");

		// Sessions list (from the laptop) shows both, with device labels and exactly one "current".
		String listJson = mockMvc.perform(get("/api/auth/sessions").cookie(laptop))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(2))
				.andReturn().getResponse().getContentAsString();

		JsonNode arr = json.readTree(listJson);
		String phoneHandle = null;
		int currentCount = 0;
		for (JsonNode n : arr) {
			if (n.get("current").asBoolean()) currentCount++;
			if ("iPhone".equals(n.get("device").asText())) phoneHandle = n.get("handle").asText();
		}
		org.junit.jupiter.api.Assertions.assertEquals(1, currentCount, "exactly one session is current");
		org.junit.jupiter.api.Assertions.assertNotNull(phoneHandle, "iPhone session should be listed");

		// The phone's session works before the kill.
		mockMvc.perform(get("/api/auth/status").cookie(phone))
				.andExpect(jsonPath("$.authenticated").value(true));

		// Laptop remotely kills the phone's session (FR-39).
		mockMvc.perform(delete("/api/auth/sessions/{handle}", phoneHandle).cookie(laptop))
				.andExpect(status().isNoContent());

		// The phone is now rejected on its next gated request; the laptop still works.
		mockMvc.perform(get("/api/system-info").cookie(phone)).andExpect(status().isUnauthorized());
		mockMvc.perform(get("/api/auth/sessions").cookie(laptop))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(1));
	}

	@Test
	void sessionsRequireSession() throws Exception {
		mockMvc.perform(get("/api/auth/sessions")).andExpect(status().isUnauthorized());
		mockMvc.perform(delete("/api/auth/sessions/whatever")).andExpect(status().isUnauthorized());
	}

	@Test
	void validateNeverResurrectsAKilledOrExpiredSession() {
		String id = sessionStore.create("Test");
		// Simulate the session being remote-killed / idle-expired out from under an in-flight request.
		redis.delete(SessionStore.KEY_PREFIX + id);
		org.junit.jupiter.api.Assertions.assertFalse(sessionStore.validate(id),
				"a deleted session must not validate");
		org.junit.jupiter.api.Assertions.assertFalse(
				Boolean.TRUE.equals(redis.hasKey(SessionStore.KEY_PREFIX + id)),
				"validate must not recreate the key (no zombie session)");
	}
}
