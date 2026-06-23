package com.argus.portfolio;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.argus.TestcontainersConfiguration;
import com.argus.marketdata.FxRateClient;
import com.argus.marketdata.FxRateRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import java.math.BigDecimal;
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

/** End-to-end corporate-actions handling: splits / ticker change / merger / ambiguous (Story 3.3). */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class CorporateActionIntegrationTest {

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
	CorporateActionRepository corporateActions;

	@Autowired
	FxRateRepository fxRates;

	@Autowired
	com.argus.security.AppCredentialRepository pinCredentials;

	@MockitoBean
	FxRateClient fxRateClient;

	private final ObjectMapper json = new ObjectMapper();

	@BeforeEach
	void reset() {
		corporateActions.deleteAll();
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

	/** Import one holding through the PDF flow and confirm it, so a real position+lot exists. */
	private void importHolding(Cookie session, String line) throws Exception {
		MockMultipartFile file = new MockMultipartFile("file", "statement.pdf",
				MediaType.APPLICATION_PDF_VALUE, PdfFixtures.withLines(List.of(line)));
		String preview = mockMvc.perform(multipart("/api/portfolio/imports").file(file).cookie(session))
				.andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString();
		long importId = json.readTree(preview).get("importId").asLong();
		mockMvc.perform(post("/api/portfolio/imports/{id}/confirm", importId).cookie(session))
				.andExpect(status().isOk());
	}

	private org.springframework.test.web.servlet.ResultActions recordAction(Cookie session, String body)
			throws Exception {
		return mockMvc.perform(post("/api/portfolio/corporate-actions").cookie(session)
				.contentType(MediaType.APPLICATION_JSON).content(body));
	}

	@Test
	void cleanSplitAutoAppliesAndPreservesTotalCost() throws Exception {
		Cookie session = login();
		importHolding(session, "AAPL 100 150.25 USD 2023-01-15"); // FX 1.35 → cadAcb 202.8375

		recordAction(session, "{\"ticker\":\"AAPL\",\"type\":\"split\",\"ratio\":2}")
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.status").value("applied"));

		mockMvc.perform(get("/api/portfolio/positions").cookie(session))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[0].ticker").value("AAPL"))
				.andExpect(jsonPath("$[0].shares").value(200))         // doubled
				.andExpect(jsonPath("$[0].costBasis").value(150.25))   // total cost preserved
				.andExpect(jsonPath("$[0].cadAcb").value(202.8375));   // CAD ACB preserved
	}

	@Test
	void ambiguousNoMatchStaysPendingAndTouchesNoPosition() throws Exception {
		Cookie session = login();
		importHolding(session, "AAPL 100 150.25 USD 2023-01-15");

		recordAction(session, "{\"ticker\":\"ZZZ\",\"type\":\"split\",\"ratio\":2}")
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.status").value("pending"))
				.andExpect(jsonPath("$.note").isNotEmpty());

		// The unrelated AAPL position is untouched.
		mockMvc.perform(get("/api/portfolio/positions").cookie(session))
				.andExpect(jsonPath("$.length()").value(1))
				.andExpect(jsonPath("$[0].shares").value(100));
	}

	@Test
	void tickerChangeRemapsThePosition() throws Exception {
		Cookie session = login();
		importHolding(session, "AAPL 100 150.25 USD 2023-01-15");

		recordAction(session, "{\"ticker\":\"AAPL\",\"type\":\"ticker_change\",\"newTicker\":\"APPL2\"}")
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.status").value("applied"));

		mockMvc.perform(get("/api/portfolio/positions").cookie(session))
				.andExpect(jsonPath("$[0].ticker").value("APPL2"))
				.andExpect(jsonPath("$[0].shares").value(100));
	}

	@Test
	void mergerStaysPendingThenConfirmApplies() throws Exception {
		Cookie session = login();
		importHolding(session, "AAPL 100 150.25 USD 2023-01-15");

		String pending = recordAction(session,
				"{\"ticker\":\"AAPL\",\"type\":\"merger\",\"ratio\":1,\"newTicker\":\"NEWCO\"}")
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.status").value("pending")) // mergers always need confirmation
				.andReturn().getResponse().getContentAsString();
		long id = json.readTree(pending).get("id").asLong();

		mockMvc.perform(post("/api/portfolio/corporate-actions/{id}/confirm", id).cookie(session))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("applied"));

		mockMvc.perform(get("/api/portfolio/positions").cookie(session))
				.andExpect(jsonPath("$[0].ticker").value("NEWCO"));
	}

	@Test
	void dismissAndDoubleConfirmAreRejected() throws Exception {
		Cookie session = login();
		// A no-match pending action to dismiss.
		String pending = recordAction(session, "{\"ticker\":\"ZZZ\",\"type\":\"merger\"}")
				.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
		long id = json.readTree(pending).get("id").asLong();

		mockMvc.perform(post("/api/portfolio/corporate-actions/{id}/dismiss", id).cookie(session))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("dismissed"));
		// Dismissing again → 409.
		mockMvc.perform(post("/api/portfolio/corporate-actions/{id}/dismiss", id).cookie(session))
				.andExpect(status().isConflict())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));

		// Auto-applied split can't be confirmed again.
		importHolding(session, "AAPL 100 150.25 USD 2023-01-15");
		String applied = recordAction(session, "{\"ticker\":\"AAPL\",\"type\":\"split\",\"ratio\":2}")
				.andReturn().getResponse().getContentAsString();
		long appliedId = json.readTree(applied).get("id").asLong();
		mockMvc.perform(post("/api/portfolio/corporate-actions/{id}/confirm", appliedId).cookie(session))
				.andExpect(status().isConflict());
	}

	@Test
	void unknownTypeIsBadRequest() throws Exception {
		Cookie session = login();
		recordAction(session, "{\"ticker\":\"AAPL\",\"type\":\"frobnicate\"}")
				.andExpect(status().isBadRequest())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
	}

	@Test
	void corporateActionEndpointsRequireASession() throws Exception {
		mockMvc.perform(get("/api/portfolio/corporate-actions")).andExpect(status().isUnauthorized());
		mockMvc.perform(post("/api/portfolio/corporate-actions")
						.contentType(MediaType.APPLICATION_JSON).content("{\"ticker\":\"AAPL\",\"type\":\"split\"}"))
				.andExpect(status().isUnauthorized());
	}
}
