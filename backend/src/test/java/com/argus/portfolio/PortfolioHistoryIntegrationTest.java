package com.argus.portfolio;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import java.time.LocalDate;
import java.time.ZoneId;
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

/** Portfolio value history capture + range query + endpoint (Story 3.6, FR-4). */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class PortfolioHistoryIntegrationTest {

	@Autowired
	MockMvc mockMvc;

	@Autowired
	StringRedisTemplate redis;

	@Autowired
	PortfolioValuePointRepository pointsRepo;

	@Autowired
	PortfolioHistoryService history;

	@Autowired
	com.argus.security.AppCredentialRepository pinCredentials;

	@MockitoBean
	FxRateClient fxRateClient;

	private static final LocalDate TODAY = LocalDate.now(ZoneId.of("America/Toronto"));

	@BeforeEach
	void reset() {
		pointsRepo.deleteAll();
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
	void valueHistoryFiltersToTheRangeAscending() throws Exception {
		Cookie session = login();
		pointsRepo.save(new PortfolioValuePoint(TODAY.minusDays(100), new BigDecimal("50.00")));
		pointsRepo.save(new PortfolioValuePoint(TODAY.minusDays(10), new BigDecimal("90.00")));
		pointsRepo.save(new PortfolioValuePoint(TODAY, new BigDecimal("100.00")));

		mockMvc.perform(get("/api/portfolio/value-history").param("range", "1M").cookie(session))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(2))                 // the 100-day-old point is excluded
				.andExpect(jsonPath("$[0].totalValueCad").value(90.00))     // ascending by date
				.andExpect(jsonPath("$[1].totalValueCad").value(100.00));
	}

	@Test
	void captureIsIdempotentPerDay() {
		history.capture();
		history.capture(); // same day → updates, not a second row

		assertEquals(1, pointsRepo.count());
		assertEquals(TODAY, pointsRepo.findAll().get(0).getCapturedOn());
	}

	@Test
	void valueHistoryRequiresASession() throws Exception {
		mockMvc.perform(get("/api/portfolio/value-history")).andExpect(status().isUnauthorized());
	}
}
