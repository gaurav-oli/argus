package com.argus.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.argus.TestcontainersConfiguration;
import jakarta.servlet.http.Cookie;
import java.time.Duration;
import java.util.Optional;
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

/** Configurable session timeout (Story 2.3): endpoint round-trip, validation, and the session-store TTL. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class SettingsIntegrationTest {

	@Autowired
	MockMvc mockMvc;

	@Autowired
	AppCredentialRepository pinCredentials;

	@Autowired
	AppSettingsRepository settingsRepo;

	@Autowired
	SettingsService settingsService;

	@Autowired
	SessionStore sessionStore;

	@Autowired
	StringRedisTemplate redis;

	@BeforeEach
	void reset() {
		pinCredentials.deleteAll();
		settingsRepo.deleteAll();
		settingsService.load(); // resync the in-memory cache to the default after clearing the row
		Set<String> keys = redis.keys("argus:*");
		if (keys != null && !keys.isEmpty()) {
			redis.delete(keys);
		}
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
	void settingsRequireSession() throws Exception {
		mockMvc.perform(get("/api/settings/session-timeout"))
				.andExpect(status().isUnauthorized());
	}

	@Test
	void defaultIsFifteenMinutes() throws Exception {
		Cookie session = login();
		mockMvc.perform(get("/api/settings/session-timeout").cookie(session))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.seconds").value(900));
	}

	@Test
	void roundTripFiniteValue() throws Exception {
		Cookie session = login();
		mockMvc.perform(put("/api/settings/session-timeout").cookie(session)
						.contentType(MediaType.APPLICATION_JSON).content("{\"seconds\":1800}"))
				.andExpect(status().isNoContent());
		mockMvc.perform(get("/api/settings/session-timeout").cookie(session))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.seconds").value(1800));
	}

	@Test
	void roundTripNever() throws Exception {
		Cookie session = login();
		mockMvc.perform(put("/api/settings/session-timeout").cookie(session)
						.contentType(MediaType.APPLICATION_JSON).content("{\"seconds\":null}"))
				.andExpect(status().isNoContent());
		mockMvc.perform(get("/api/settings/session-timeout").cookie(session))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.seconds").doesNotExist());
	}

	@Test
	void tooShortRejected() throws Exception {
		Cookie session = login();
		mockMvc.perform(put("/api/settings/session-timeout").cookie(session)
						.contentType(MediaType.APPLICATION_JSON).content("{\"seconds\":30}"))
				.andExpect(status().isBadRequest());
	}

	@Test
	void sessionStoreSetsTtlForFiniteTimeout() {
		settingsService.setSessionTimeout(Optional.of(Duration.ofSeconds(120)));
		String id = sessionStore.create();
		Long ttl = redis.getExpire(SessionStore.KEY_PREFIX + id);
		assertNotNull(ttl);
		assertTrue(ttl > 0 && ttl <= 120, "finite timeout should set a positive TTL, was " + ttl);
	}

	@Test
	void sessionStoreSetsNoExpiryForNever() {
		settingsService.setSessionTimeout(Optional.empty());
		String id = sessionStore.create();
		// -1 = key exists with no expiry.
		assertEquals(-1L, redis.getExpire(SessionStore.KEY_PREFIX + id));
		assertTrue(sessionStore.validate(id), "Never session should validate");
	}

	@Test
	void switchingToNeverPersistsExistingSession() {
		settingsService.setSessionTimeout(Optional.of(Duration.ofSeconds(120)));
		String id = sessionStore.create();
		assertTrue(redis.getExpire(SessionStore.KEY_PREFIX + id) > 0, "finite session should have a TTL");
		settingsService.setSessionTimeout(Optional.empty()); // → Never
		assertEquals(-1L, redis.getExpire(SessionStore.KEY_PREFIX + id),
				"switching to Never must strip the existing TTL");
	}

	@Test
	void switchingToFiniteAppliesTtlToExistingSession() {
		settingsService.setSessionTimeout(Optional.empty());
		String id = sessionStore.create();
		assertEquals(-1L, redis.getExpire(SessionStore.KEY_PREFIX + id));
		settingsService.setSessionTimeout(Optional.of(Duration.ofSeconds(120))); // → finite
		Long ttl = redis.getExpire(SessionStore.KEY_PREFIX + id);
		assertTrue(ttl > 0 && ttl <= 120, "switching to finite must apply a TTL, was " + ttl);
	}

	@Test
	void emptyBodyIsBadRequest() throws Exception {
		Cookie session = login();
		mockMvc.perform(put("/api/settings/session-timeout").cookie(session)
						.contentType(MediaType.APPLICATION_JSON))
				.andExpect(status().isBadRequest());
	}
}
