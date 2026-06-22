package com.argus.security.webauthn;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
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

/**
 * WebAuthn ceremony wiring (Story 2.2) against real Postgres + Redis. The full Face-ID assertion
 * needs a platform authenticator, so the deep happy-path is a manual iPhone step; here we verify
 * the gate (register requires a session, login endpoints are allowlisted), that start ceremonies
 * produce valid options + a ceremony handle, and that a garbage assertion is rejected 401.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class WebAuthnFlowIntegrationTest {

	@Autowired
	MockMvc mockMvc;

	@Autowired
	WebAuthnCredentialRepository credentials;

	@Autowired
	com.argus.security.AppCredentialRepository pinCredentials;

	@Autowired
	StringRedisTemplate redis;

	@BeforeEach
	void reset() {
		credentials.deleteAll();
		pinCredentials.deleteAll();
		Set<String> keys = redis.keys("argus:*");
		if (keys != null && !keys.isEmpty()) {
			redis.delete(keys);
		}
	}

	@Test
	void registerStartRequiresSession() throws Exception {
		mockMvc.perform(post("/api/auth/webauthn/register/start"))
				.andExpect(status().isUnauthorized());
	}

	@Test
	void loginStartIsAllowlistedAndReturnsOptions() throws Exception {
		mockMvc.perform(post("/api/auth/webauthn/login/start"))
				.andExpect(status().isOk())
				.andExpect(header().exists(WebAuthnController.CEREMONY_HEADER))
				.andExpect(jsonPath("$.publicKey.challenge").exists());
	}

	@Test
	void loginFinishWithGarbageIsRejected() throws Exception {
		// Start to get a valid ceremony handle, then submit a bogus assertion.
		String ceremony = mockMvc.perform(post("/api/auth/webauthn/login/start"))
				.andExpect(status().isOk())
				.andReturn().getResponse().getHeader(WebAuthnController.CEREMONY_HEADER);

		mockMvc.perform(post("/api/auth/webauthn/login/finish")
						.header(WebAuthnController.CEREMONY_HEADER, ceremony)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"not\":\"a credential\"}"))
				.andExpect(status().isUnauthorized());
	}

	@Test
	void loginFinishWithUnknownCeremonyIsRejected() throws Exception {
		mockMvc.perform(post("/api/auth/webauthn/login/finish")
						.header(WebAuthnController.CEREMONY_HEADER, "no-such-ceremony")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{}"))
				.andExpect(status().isUnauthorized());
	}

	@Test
	void statusReportsNoPasskeyAndCredentialsListGated() throws Exception {
		mockMvc.perform(get("/api/auth/status"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.passkeyEnrolled").value(false));
		// The management list is session-gated.
		mockMvc.perform(get("/api/auth/webauthn/credentials"))
				.andExpect(status().isUnauthorized());
	}

	@Test
	void registerStartWithSessionReturnsOptions() throws Exception {
		// Set a PIN and log in to get a session cookie, then enroll-start.
		mockMvc.perform(post("/api/auth/pin").contentType(MediaType.APPLICATION_JSON).content("{\"pin\":\"1234\"}"))
				.andExpect(status().isCreated());
		Cookie session = mockMvc.perform(post("/api/auth/login")
						.contentType(MediaType.APPLICATION_JSON).content("{\"pin\":\"1234\"}"))
				.andExpect(status().isOk())
				.andReturn().getResponse().getCookie("ARGUS_SESSION");

		mockMvc.perform(post("/api/auth/webauthn/register/start").cookie(session))
				.andExpect(status().isOk())
				.andExpect(header().exists(WebAuthnController.CEREMONY_HEADER))
				.andExpect(jsonPath("$.publicKey.challenge").exists())
				.andExpect(jsonPath("$.publicKey.rp.id").value("localhost"));
	}

	@Test
	void registerFinishWithGarbageIsRejected() throws Exception {
		mockMvc.perform(post("/api/auth/pin").contentType(MediaType.APPLICATION_JSON).content("{\"pin\":\"1234\"}"))
				.andExpect(status().isCreated());
		Cookie session = mockMvc.perform(post("/api/auth/login")
						.contentType(MediaType.APPLICATION_JSON).content("{\"pin\":\"1234\"}"))
				.andExpect(status().isOk())
				.andReturn().getResponse().getCookie("ARGUS_SESSION");

		String ceremony = mockMvc.perform(post("/api/auth/webauthn/register/start").cookie(session))
				.andExpect(status().isOk())
				.andReturn().getResponse().getHeader(WebAuthnController.CEREMONY_HEADER);

		mockMvc.perform(post("/api/auth/webauthn/register/finish")
						.cookie(session)
						.header(WebAuthnController.CEREMONY_HEADER, ceremony)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"not\":\"a credential\"}"))
				.andExpect(status().isBadRequest());
	}

	@Test
	void revokeWithMalformedIdIsBadRequest() throws Exception {
		mockMvc.perform(post("/api/auth/pin").contentType(MediaType.APPLICATION_JSON).content("{\"pin\":\"1234\"}"))
				.andExpect(status().isCreated());
		Cookie session = mockMvc.perform(post("/api/auth/login")
						.contentType(MediaType.APPLICATION_JSON).content("{\"pin\":\"1234\"}"))
				.andExpect(status().isOk())
				.andReturn().getResponse().getCookie("ARGUS_SESSION");

		mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
						.delete("/api/auth/webauthn/credentials/{id}", "!!!not-base64!!!").cookie(session))
				.andExpect(status().isBadRequest());
	}
}
