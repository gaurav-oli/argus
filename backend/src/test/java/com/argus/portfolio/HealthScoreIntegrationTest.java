package com.argus.portfolio;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.argus.TestcontainersConfiguration;
import com.argus.marketdata.FxRateClient;
import jakarta.servlet.http.Cookie;
import java.math.BigDecimal;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** Health-score endpoint over a real holding (Story 3.8, FR-6). */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class HealthScoreIntegrationTest {

	@Autowired
	MockMvc mockMvc;

	@Autowired
	StringRedisTemplate redis;

	@Autowired
	PositionRepository positions;

	@Autowired
	PositionLotRepository lots;

	@Autowired
	HealthScoreRepository scores;

	@Autowired
	com.argus.security.AppCredentialRepository pinCredentials;

	@MockitoBean
	FxRateClient fxRateClient;

	@BeforeEach
	void reset() {
		scores.deleteAll();
		lots.deleteAll();
		positions.deleteAll();
		pinCredentials.deleteAll();
		Set<String> keys = redis.keys("argus:*");
		if (keys != null && !keys.isEmpty()) {
			redis.delete(keys);
		}
		when(fxRateClient.sourceName()).thenReturn("test-fx");
		when(fxRateClient.usdCadOn(any())).thenReturn(Optional.of(new BigDecimal("1.35")));
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
	void healthScoreReflectsHoldings() throws Exception {
		Cookie session = login();
		// Single holding → 100% concentration (−20 single, −20 top3) + 1-holding diversification (−16) = 44.
		mockMvc.perform(post("/api/portfolio/positions").cookie(session)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"ticker\":\"AAPL\",\"shares\":10,\"costBasis\":100,\"currency\":\"USD\",\"acquisitionDate\":\"2023-01-15\"}"))
				.andExpect(status().isCreated());

		mockMvc.perform(get("/api/portfolio/health-score").cookie(session))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.score").value(44))
				.andExpect(jsonPath("$.deductions.length()").value(3));
	}

	@Test
	void emptyPortfolioScores100() throws Exception {
		Cookie session = login();
		mockMvc.perform(get("/api/portfolio/health-score").cookie(session))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.score").value(100));
	}

	@Test
	void healthScoreRequiresASession() throws Exception {
		mockMvc.perform(get("/api/portfolio/health-score")).andExpect(status().isUnauthorized());
	}
}
