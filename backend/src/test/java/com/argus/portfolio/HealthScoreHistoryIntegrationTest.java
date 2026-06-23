package com.argus.portfolio;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.argus.TestcontainersConfiguration;
import jakarta.servlet.http.Cookie;
import java.time.LocalDate;
import java.time.ZoneId;
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

/** Health-score 30-day trend endpoint (Story 3.9, FR-7). */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class HealthScoreHistoryIntegrationTest {

	@Autowired
	MockMvc mockMvc;

	@Autowired
	StringRedisTemplate redis;

	@Autowired
	HealthScoreRepository scores;

	@Autowired
	com.argus.security.AppCredentialRepository pinCredentials;

	private static final LocalDate TODAY = LocalDate.now(ZoneId.of("America/Toronto"));

	@BeforeEach
	void reset() {
		scores.deleteAll();
		pinCredentials.deleteAll();
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
	void historyReturnsTheWindowedSeriesAscending() throws Exception {
		Cookie session = login();
		scores.save(new HealthScore(TODAY.minusDays(40), 60, "[]")); // outside the 30d window
		scores.save(new HealthScore(TODAY.minusDays(5), 70, "[]"));
		scores.save(new HealthScore(TODAY, 80, "[]"));

		mockMvc.perform(get("/api/portfolio/health-score/history").param("days", "30").cookie(session))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(2))
				.andExpect(jsonPath("$[0].score").value(70)) // ascending
				.andExpect(jsonPath("$[1].score").value(80));
	}

	@Test
	void emptyHistoryReturnsEmptyArray() throws Exception {
		Cookie session = login();
		mockMvc.perform(get("/api/portfolio/health-score/history").cookie(session))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(0));
	}

	@Test
	void historyRequiresASession() throws Exception {
		mockMvc.perform(get("/api/portfolio/health-score/history")).andExpect(status().isUnauthorized());
	}
}
