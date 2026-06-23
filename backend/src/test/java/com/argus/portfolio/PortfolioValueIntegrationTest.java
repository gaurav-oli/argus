package com.argus.portfolio;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.argus.TestcontainersConfiguration;
import com.argus.marketdata.FxRateClient;
import com.argus.marketdata.FxRateRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
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
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** Live portfolio value endpoint + tick-driven snapshot (Story 3.4). Price ticks driven directly. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class PortfolioValueIntegrationTest {

	@Autowired
	MockMvc mockMvc;

	@Autowired
	StringRedisTemplate redis;

	@Autowired
	PositionRepository positions;

	@Autowired
	PositionLotRepository lots;

	@Autowired
	PortfolioImportRepository imports;

	@Autowired
	FxRateRepository fxRates;

	@Autowired
	LivePortfolioService live;

	@Autowired
	com.argus.security.AppCredentialRepository pinCredentials;

	@MockitoBean
	FxRateClient fxRateClient;

	private final ObjectMapper json = new ObjectMapper();

	private static final java.time.Instant REGULAR = ZonedDateTime
			.of(LocalDate.of(2023, 6, 15), LocalTime.of(14, 0), ZoneId.of("America/New_York")).toInstant();

	@BeforeEach
	void reset() {
		lots.deleteAll();
		positions.deleteAll();
		imports.deleteAll();
		fxRates.deleteAll();
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

	private void importHolding(Cookie session, String line) throws Exception {
		MockMultipartFile file = new MockMultipartFile("file", "statement.pdf",
				MediaType.APPLICATION_PDF_VALUE, PdfFixtures.withLines(List.of(line)));
		String preview = mockMvc.perform(multipart("/api/portfolio/imports").file(file).cookie(session))
				.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
		long importId = json.readTree(preview).get("importId").asLong();
		mockMvc.perform(post("/api/portfolio/imports/{id}/confirm", importId).cookie(session))
				.andExpect(status().isOk());
	}

	@Test
	void valueEndpointReflectsAPriceTick() throws Exception {
		Cookie session = login();
		importHolding(session, "AAPL 100 150.25 USD 2023-01-15");

		// Before any tick: AAPL present but unpriced.
		mockMvc.perform(get("/api/portfolio/value").cookie(session))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.positions[0].ticker").value("AAPL"))
				.andExpect(jsonPath("$.positions[0].price").doesNotExist());

		live.onPriceTick("AAPL", new BigDecimal("200"), REGULAR);

		mockMvc.perform(get("/api/portfolio/value").cookie(session))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.positions[0].price").value(200))
				.andExpect(jsonPath("$.positions[0].marketValue").value(20000.00)) // 100 × 200
				.andExpect(jsonPath("$.positions[0].afterHours").value(false))
				.andExpect(jsonPath("$.totalValueCad").value(27000.00));            // 20000 × 1.35
	}

	@Test
	void valueEndpointRequiresASession() throws Exception {
		mockMvc.perform(get("/api/portfolio/value")).andExpect(status().isUnauthorized());
	}
}
