package com.argus.security;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
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

/** Escalating failed-attempt lockout (FR-38 / Story 2.6). Defaults: 3 → 30s, 5 → 10m, 10 → full. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class LockoutIntegrationTest {

	@Autowired
	MockMvc mockMvc;

	@Autowired
	AppCredentialRepository pinCredentials;

	@Autowired
	StringRedisTemplate redis;

	@BeforeEach
	void reset() {
		pinCredentials.deleteAll();
		Set<String> keys = redis.keys("argus:*");
		if (keys != null && !keys.isEmpty()) {
			redis.delete(keys);
		}
	}

	private void setPin() throws Exception {
		mockMvc.perform(post("/api/auth/pin").contentType(MediaType.APPLICATION_JSON).content("{\"pin\":\"1234\"}"))
				.andExpect(status().isCreated());
	}

	private org.springframework.test.web.servlet.ResultActions attempt(String pin) throws Exception {
		return mockMvc.perform(post("/api/auth/login")
				.contentType(MediaType.APPLICATION_JSON).content("{\"pin\":\"" + pin + "\"}"));
	}

	@Test
	void thirdFailureReturns429WithRetryAfter() throws Exception {
		setPin();
		attempt("0000").andExpect(status().isUnauthorized());
		attempt("0000").andExpect(status().isUnauthorized());
		attempt("0000")
				.andExpect(status().isTooManyRequests())
				.andExpect(header().exists("Retry-After"))
				.andExpect(jsonPath("$.fullyLocked").value(false))
				.andExpect(jsonPath("$.retryAfterSeconds").value(30));
	}

	@Test
	void lockedRefusesEvenTheCorrectPin() throws Exception {
		setPin();
		attempt("0000").andExpect(status().isUnauthorized());
		attempt("0000").andExpect(status().isUnauthorized());
		attempt("0000").andExpect(status().isTooManyRequests());
		// Correct PIN is still refused while the lockout is in effect, and no session is created.
		attempt("1234")
				.andExpect(status().isTooManyRequests())
				.andExpect(cookie().doesNotExist("ARGUS_SESSION"));
	}

	@Test
	void fifthFailureEscalatesToTenMinuteLockout() throws Exception {
		setPin();
		// Seed 4 prior failures (simulating earlier windows) so the next wrong PIN crosses the
		// alert threshold (5) → 10-minute lockout, without waiting out the 30s tier.
		redis.opsForValue().set(LockoutService.KEY_FAILS, "4");
		attempt("0000")
				.andExpect(status().isTooManyRequests())
				.andExpect(jsonPath("$.fullyLocked").value(false))
				.andExpect(jsonPath("$.retryAfterSeconds").value(600));
	}

	@Test
	void successResetsTheCounter() throws Exception {
		setPin();
		attempt("0000").andExpect(status().isUnauthorized());
		attempt("0000").andExpect(status().isUnauthorized());
		attempt("1234").andExpect(status().isOk()); // resets fails
		// Two fresh failures only — should be 401s, not a 429 (counter was reset).
		attempt("0000").andExpect(status().isUnauthorized());
		attempt("0000").andExpect(status().isUnauthorized());
	}

	@Test
	void tenthFailureFullyLocksAndAnotherDeviceClears() throws Exception {
		setPin();
		// Authenticate first to get a session cookie (another signed-in device), then seed the
		// failure counter to 9 so the next wrong PIN crosses the full-lock threshold without waiting
		// out the intermediate timed lockouts.
		Cookie session = attempt("1234").andExpect(status().isOk())
				.andReturn().getResponse().getCookie("ARGUS_SESSION");
		redis.opsForValue().set(LockoutService.KEY_FAILS, "9");

		attempt("0000")
				.andExpect(status().isLocked())
				.andExpect(jsonPath("$.fullyLocked").value(true));
		// Full lock refuses the correct PIN too.
		attempt("1234").andExpect(status().isLocked());

		// The already-signed-in device clears the lockout (gated endpoint).
		mockMvc.perform(post("/api/auth/lockout/clear").cookie(session))
				.andExpect(status().isNoContent());

		// Recovery: correct PIN works again.
		attempt("1234").andExpect(status().isOk());
	}

	@Test
	void clearLockoutRequiresSession() throws Exception {
		mockMvc.perform(post("/api/auth/lockout/clear"))
				.andExpect(status().isUnauthorized());
	}
}
