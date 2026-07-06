package com.argus.portfolio;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.argus.TestcontainersConfiguration;
import com.argus.marketdata.FxRateClient;
import com.argus.marketdata.FxRateRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Assertions;
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

/** End-to-end portfolio PDF import + per-lot CAD ACB (Stories 3.1 + 3.2). */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class PortfolioImportIntegrationTest {

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
	com.argus.security.AppCredentialRepository pinCredentials;

	/** Stubbed so tests never hit the live Bank of Canada endpoint; returns a fixed USD/CAD. */
	@MockitoBean
	FxRateClient fxRateClient;

	private final ObjectMapper json = new ObjectMapper();

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

	private static MockMultipartFile pdf(List<String> lines) {
		return new MockMultipartFile("file", "statement.pdf", MediaType.APPLICATION_PDF_VALUE,
				PdfFixtures.withLines(lines));
	}

	@Test
	void uploadPreviewsThenConfirmPersistsHoldingsWithFlagsPreserved() throws Exception {
		Cookie session = login();

		// AAPL is fully parseable; TD is missing its cost basis → must be kept and flagged.
		MockMultipartFile file = pdf(List.of(
				"Holdings",
				"AAPL 100 150.25 USD 2023-01-15",
				"TD 25 2024-03-10"));

		String previewJson = mockMvc.perform(multipart("/api/portfolio/imports").file(file).cookie(session))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.status").value("pending"))
				.andExpect(jsonPath("$.holdings.length()").value(2))
				.andReturn().getResponse().getContentAsString();

		JsonNode preview = json.readTree(previewJson);
		long importId = preview.get("importId").asLong();

		// Nothing is persisted to positions on upload alone (confirm-before-overwrite).
		mockMvc.perform(get("/api/portfolio/positions").cookie(session))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(0));

		// Confirm commits the staged holdings.
		mockMvc.perform(post("/api/portfolio/imports/{id}/confirm", importId).cookie(session))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(2));

		// Listed back, sorted by ticker: AAPL clean, TD flagged with cost basis preserved as null.
		mockMvc.perform(get("/api/portfolio/positions").cookie(session))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(2))
				.andExpect(jsonPath("$[0].ticker").value("AAPL"))
				.andExpect(jsonPath("$[0].needsReview").value(false))
				.andExpect(jsonPath("$[0].costBasis").value(150.25))
				.andExpect(jsonPath("$[1].ticker").value("TD"))
				.andExpect(jsonPath("$[1].needsReview").value(true))
				.andExpect(jsonPath("$[1].costBasis").doesNotExist());
	}

	@Test
	void confirmingTwiceIsRejected() throws Exception {
		Cookie session = login();
		String previewJson = mockMvc.perform(multipart("/api/portfolio/imports")
						.file(pdf(List.of("AAPL 100 150.25 USD 2023-01-15"))).cookie(session))
				.andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString();
		long importId = json.readTree(previewJson).get("importId").asLong();

		mockMvc.perform(post("/api/portfolio/imports/{id}/confirm", importId).cookie(session))
				.andExpect(status().isOk());
		mockMvc.perform(post("/api/portfolio/imports/{id}/confirm", importId).cookie(session))
				.andExpect(status().isConflict())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
	}

	@Test
	void nonPdfUploadReturnsProblemDetails() throws Exception {
		Cookie session = login();
		MockMultipartFile notPdf = new MockMultipartFile("file", "notes.txt", MediaType.TEXT_PLAIN_VALUE,
				"these are not holdings".getBytes());

		mockMvc.perform(multipart("/api/portfolio/imports").file(notPdf).cookie(session))
				.andExpect(status().isBadRequest())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
	}

	@Test
	void emptyHoldingsPdfSucceedsWithAMessageAndNoPositions() throws Exception {
		Cookie session = login();
		mockMvc.perform(multipart("/api/portfolio/imports")
						.file(pdf(List.of("This statement contains no holdings table."))).cookie(session))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.holdings.length()").value(0))
				.andExpect(jsonPath("$.message").isNotEmpty());
	}

	@Test
	void importEndpointsRequireASession() throws Exception {
		Assertions.assertNotNull(mockMvc);
		mockMvc.perform(get("/api/portfolio/positions")).andExpect(status().isUnauthorized());
		mockMvc.perform(multipart("/api/portfolio/imports").file(pdf(List.of("AAPL 100 150.25 USD 2023-01-15"))))
				.andExpect(status().isUnauthorized());
	}

	// ---- Story 3.2: CAD ACB at purchase-time FX ----

	private long confirmAndGetFirstPositionId(Cookie session, List<String> lines) throws Exception {
		String previewJson = mockMvc.perform(multipart("/api/portfolio/imports").file(pdf(lines)).cookie(session))
				.andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString();
		long importId = json.readTree(previewJson).get("importId").asLong();
		String confirmed = mockMvc.perform(post("/api/portfolio/imports/{id}/confirm", importId).cookie(session))
				.andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();
		return json.readTree(confirmed).get(0).get("id").asLong();
	}

	@Test
	void usdHoldingGetsCadAcbAtPurchaseFx() throws Exception {
		Cookie session = login();
		// Stubbed FX = 1.35 → CAD ACB = 150.25 * 1.35 = 202.8375.
		confirmAndGetFirstPositionId(session, List.of("AAPL 100 150.25 USD 2023-01-15"));

		mockMvc.perform(get("/api/portfolio/positions").cookie(session))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[0].ticker").value("AAPL"))
				.andExpect(jsonPath("$[0].costBasis").value(150.25))
				.andExpect(jsonPath("$[0].cadAcb").value(202.8375))
				.andExpect(jsonPath("$[0].fxEstimated").value(false));
	}

	@Test
	void cadHoldingUsesFxOneAndIsNotEstimated() throws Exception {
		Cookie session = login();
		confirmAndGetFirstPositionId(session, List.of("RY 100 8000.00 CAD 2023-01-15"));

		mockMvc.perform(get("/api/portfolio/positions").cookie(session))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[0].ticker").value("RY"))
				.andExpect(jsonPath("$[0].cadAcb").value(8000.0))
				.andExpect(jsonPath("$[0].fxEstimated").value(false));
	}

	@Test
	void holdingWithoutPurchaseDateIsFlaggedFxEstimated() throws Exception {
		Cookie session = login();
		// No date → true purchase FX isn't derivable, so the CAD ACB is estimated at the latest stubbed
		// rate (500 * 1.35 = 675) and flagged fxEstimated; the user can confirm the real rate later.
		confirmAndGetFirstPositionId(session, List.of("NVDA 10 500.00 USD"));

		mockMvc.perform(get("/api/portfolio/positions").cookie(session))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[0].ticker").value("NVDA"))
				.andExpect(jsonPath("$[0].fxEstimated").value(true))
				.andExpect(jsonPath("$[0].cadAcb").value(675.0));
	}

	@Test
	void confirmingFxRateClearsTheEstimateAndRecomputesAcb() throws Exception {
		Cookie session = login();
		long id = confirmAndGetFirstPositionId(session, List.of("NVDA 10 500.00 USD")); // estimated at latest FX

		// User supplies an explicit purchase rate → estimate clears, CAD ACB = 500.00 * 1.40 = 700.0000.
		mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
						.put("/api/portfolio/positions/{id}/fx", id).cookie(session)
						.contentType(MediaType.APPLICATION_JSON).content("{\"rate\":1.40}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.fxEstimated").value(false))
				.andExpect(jsonPath("$.cadAcb").value(700.0));

		mockMvc.perform(get("/api/portfolio/positions").cookie(session))
				.andExpect(jsonPath("$[0].fxEstimated").value(false))
				.andExpect(jsonPath("$[0].cadAcb").value(700.0));
	}

	@Test
	void confirmFxRequiresRateOrDate() throws Exception {
		Cookie session = login();
		long id = confirmAndGetFirstPositionId(session, List.of("NVDA 10 500.00 USD"));

		mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
						.put("/api/portfolio/positions/{id}/fx", id).cookie(session)
						.contentType(MediaType.APPLICATION_JSON).content("{}"))
				.andExpect(status().isBadRequest())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
	}

	@Test
	void confirmFxRejectsBothRateAndDate() throws Exception {
		Cookie session = login();
		long id = confirmAndGetFirstPositionId(session, List.of("NVDA 10 500.00 USD"));

		mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
						.put("/api/portfolio/positions/{id}/fx", id).cookie(session)
						.contentType(MediaType.APPLICATION_JSON).content("{\"rate\":1.40,\"date\":\"2023-01-15\"}"))
				.andExpect(status().isBadRequest())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
	}

	@Test
	void confirmFxRejectsOutOfRangeOrTooPreciseRate() throws Exception {
		Cookie session = login();
		long id = confirmAndGetFirstPositionId(session, List.of("NVDA 10 500.00 USD"));

		// Absurd magnitude → would overflow numeric(18,8).
		mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
						.put("/api/portfolio/positions/{id}/fx", id).cookie(session)
						.contentType(MediaType.APPLICATION_JSON).content("{\"rate\":99999}"))
				.andExpect(status().isBadRequest());
		// More than 8 decimal places → would silently round.
		mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
						.put("/api/portfolio/positions/{id}/fx", id).cookie(session)
						.contentType(MediaType.APPLICATION_JSON).content("{\"rate\":1.123456789}"))
				.andExpect(status().isBadRequest());
	}
}
