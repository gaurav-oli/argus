package com.argus.portfolio;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.argus.TestcontainersConfiguration;
import com.argus.marketdata.FxRateClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import java.math.BigDecimal;
import java.time.LocalDate;
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

/** Manual add/edit/remove + audit (Story 3.7, FR-5). */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class ManualPositionIntegrationTest {

	@Autowired
	MockMvc mockMvc;

	@Autowired
	StringRedisTemplate redis;

	@Autowired
	PositionRepository positions;

	@Autowired
	PositionLotRepository lots;

	@Autowired
	PositionAuditRepository auditRepo;

	@Autowired
	PositionAcbService acbService;

	@Autowired
	com.argus.security.AppCredentialRepository pinCredentials;

	@MockitoBean
	FxRateClient fxRateClient;

	private final ObjectMapper json = new ObjectMapper();

	@BeforeEach
	void reset() {
		auditRepo.deleteAll();
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

	private long addAapl(Cookie session) throws Exception {
		String body = mockMvc.perform(post("/api/portfolio/positions").cookie(session)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"ticker\":\"aapl\",\"shares\":10,\"costBasis\":1500,\"currency\":\"usd\",\"acquisitionDate\":\"2023-01-15\"}"))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.ticker").value("AAPL"))   // uppercased
				.andReturn().getResponse().getContentAsString();
		return json.readTree(body).get("id").asLong();
	}

	@Test
	void addThenListShowsPositionWithCadAcb() throws Exception {
		Cookie session = login();
		addAapl(session);

		mockMvc.perform(get("/api/portfolio/positions").cookie(session))
				.andExpect(jsonPath("$[0].ticker").value("AAPL"))
				.andExpect(jsonPath("$[0].shares").value(10))
				.andExpect(jsonPath("$[0].costBasis").value(1500))
				.andExpect(jsonPath("$[0].cadAcb").value(2025.0000)); // 1500 × 1.35
	}

	@Test
	void editUpdatesSharesAndCost() throws Exception {
		Cookie session = login();
		long id = addAapl(session);

		mockMvc.perform(put("/api/portfolio/positions/{id}", id).cookie(session)
						.contentType(MediaType.APPLICATION_JSON).content("{\"shares\":20,\"costBasis\":3000}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.shares").value(20))
				.andExpect(jsonPath("$.costBasis").value(3000));
	}

	@Test
	void editingDataOnAMultiLotPositionIsRejected() throws Exception {
		Cookie session = login();
		// Build a 2-lot position directly (the import/manual path makes single-lot positions).
		Position p = positions.save(new Position("MULTI", null, null, null, "USD",
				LocalDate.of(2023, 1, 15), false, "manual"));
		lots.save(new PositionLot(p.getId(), new BigDecimal("10"), new BigDecimal("1000"), "USD",
				LocalDate.of(2023, 1, 15), new BigDecimal("1.3"), false));
		lots.save(new PositionLot(p.getId(), new BigDecimal("20"), new BigDecimal("3000"), "USD",
				LocalDate.of(2023, 6, 15), new BigDecimal("1.4"), false));
		acbService.recompute(p);

		mockMvc.perform(put("/api/portfolio/positions/{id}", p.getId()).cookie(session)
						.contentType(MediaType.APPLICATION_JSON).content("{\"shares\":5}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.detail").isNotEmpty());
	}

	@Test
	void removeDeletesPositionAndLots() throws Exception {
		Cookie session = login();
		long id = addAapl(session);

		mockMvc.perform(delete("/api/portfolio/positions/{id}", id).cookie(session))
				.andExpect(status().isNoContent());

		mockMvc.perform(get("/api/portfolio/positions").cookie(session))
				.andExpect(jsonPath("$.length()").value(0));
		org.junit.jupiter.api.Assertions.assertEquals(0, lots.count());
	}

	@Test
	void everyChangeIsAudited() throws Exception {
		Cookie session = login();
		long id = addAapl(session);
		mockMvc.perform(delete("/api/portfolio/positions/{id}", id).cookie(session))
				.andExpect(status().isNoContent());

		mockMvc.perform(get("/api/portfolio/audit").cookie(session))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[0].action").value("removed"))  // newest first
				.andExpect(jsonPath("$[1].action").value("created"));
	}

	@Test
	void manualEndpointsRequireASession() throws Exception {
		mockMvc.perform(get("/api/portfolio/audit")).andExpect(status().isUnauthorized());
		mockMvc.perform(post("/api/portfolio/positions")
						.contentType(MediaType.APPLICATION_JSON).content("{\"ticker\":\"AAPL\"}"))
				.andExpect(status().isUnauthorized());
		mockMvc.perform(delete("/api/portfolio/positions/{id}", 1)).andExpect(status().isUnauthorized());
	}
}
