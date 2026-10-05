package com.argus.portfolio;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.argus.TestcontainersConfiguration;
import com.argus.security.CurrentUserContext;
import com.argus.security.TestUserSessions;
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
	com.argus.security.AppUserRepository appUsers;

	@Autowired
	com.argus.security.SessionStore sessions;

	private static final LocalDate TODAY = LocalDate.now(ZoneId.of("America/Toronto"));

	@BeforeEach
	void reset() {
		scores.deleteAll();
		Set<String> keys = redis.keys("argus:*");
		if (keys != null && !keys.isEmpty()) {
			redis.delete(keys);
		}
	}

	/** A real signed-in {@link com.argus.security.AppUser} (Phase 2: every portfolio row needs one). */
	private Cookie login() {
		return TestUserSessions.loginAsNewUser(appUsers, sessions);
	}

	@Test
	void historyReturnsTheWindowedSeriesAscending() throws Exception {
		Cookie session = login();
		// Direct repo writes, not through a request — must be done as the SAME signed-in user as `session`.
		CurrentUserContext.runAs(sessions.userId(session.getValue()).orElseThrow(), () -> {
			scores.save(new HealthScore(TODAY.minusDays(40), 60, "[]")); // outside the 30d window
			scores.save(new HealthScore(TODAY.minusDays(5), 70, "[]"));
			scores.save(new HealthScore(TODAY, 80, "[]"));
		});

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
