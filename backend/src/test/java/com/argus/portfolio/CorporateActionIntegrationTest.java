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
	PositionAcbService acbService;

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

	// ---- Code-review follow-ups ----

	/** Build a multi-lot position directly (the import path makes single-lot positions). */
	private Position twoLotPosition(String ticker) {
		Position p = positions.save(new Position(ticker, null, null, null, "USD",
				java.time.LocalDate.of(2023, 1, 15), false, "manual"));
		lots.save(new PositionLot(p.getId(), new BigDecimal("10"), new BigDecimal("1000.00"), "USD",
				java.time.LocalDate.of(2023, 1, 15), new BigDecimal("1.30"), false));
		lots.save(new PositionLot(p.getId(), new BigDecimal("20"), new BigDecimal("3000.00"), "USD",
				java.time.LocalDate.of(2023, 6, 15), new BigDecimal("1.40"), false));
		acbService.recompute(p); // shares 30, costBasis 4000.00, cadAcb 1000*1.30 + 3000*1.40 = 5500
		return p;
	}

	@Test
	void splitScalesAllLotsAndPreservesTotalCostAcrossLots() throws Exception {
		Cookie session = login();
		twoLotPosition("MULTI");

		recordAction(session, "{\"ticker\":\"MULTI\",\"type\":\"split\",\"ratio\":2}")
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.status").value("applied"));

		mockMvc.perform(get("/api/portfolio/positions").cookie(session))
				.andExpect(jsonPath("$[0].ticker").value("MULTI"))
				.andExpect(jsonPath("$[0].shares").value(60))        // (10+20) × 2
				.andExpect(jsonPath("$[0].costBasis").value(4000.0)) // preserved
				.andExpect(jsonPath("$[0].cadAcb").value(5500.0));   // preserved
	}

	@Test
	void multipleHoldingsWithSameTickerStayPending() throws Exception {
		Cookie session = login();
		twoLotPosition("DUP");  // two positions, same ticker, created directly
		twoLotPosition("DUP");

		recordAction(session, "{\"ticker\":\"DUP\",\"type\":\"split\",\"ratio\":2}")
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.status").value("pending"));

		// Neither position was scaled (still 30 shares each).
		mockMvc.perform(get("/api/portfolio/positions").cookie(session))
				.andExpect(jsonPath("$.length()").value(2))
				.andExpect(jsonPath("$[0].shares").value(30))
				.andExpect(jsonPath("$[1].shares").value(30));
	}

	@Test
	void tickerChangeOntoAnExistingHoldingIsPendingNotApplied() throws Exception {
		Cookie session = login();
		importHolding(session, "AAPL 100 150.25 USD 2023-01-15");
		importHolding(session, "TSLA 5 700.00 USD 2023-02-01");

		// Renaming AAPL → TSLA would collide with the held TSLA → must NOT auto-apply.
		recordAction(session, "{\"ticker\":\"AAPL\",\"type\":\"ticker_change\",\"newTicker\":\"TSLA\"}")
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.status").value("pending"))
				.andExpect(jsonPath("$.note").isNotEmpty());

		// AAPL is untouched (still present, not renamed).
		mockMvc.perform(get("/api/portfolio/positions").cookie(session))
				.andExpect(jsonPath("$[0].ticker").value("AAPL"));
	}

	@Test
	void ratioOutOfRangeIsBadRequest() throws Exception {
		Cookie session = login();
		recordAction(session, "{\"ticker\":\"AAPL\",\"type\":\"split\",\"ratio\":99999}")
				.andExpect(status().isBadRequest())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
	}

	@Test
	void mergerWithNoRatioOrNewTickerCannotConfirm() throws Exception {
		Cookie session = login();
		importHolding(session, "AAPL 100 150.25 USD 2023-01-15");
		String pending = recordAction(session, "{\"ticker\":\"AAPL\",\"type\":\"merger\"}")
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.status").value("pending"))
				.andReturn().getResponse().getContentAsString();
		long id = json.readTree(pending).get("id").asLong();

		mockMvc.perform(post("/api/portfolio/corporate-actions/{id}/confirm", id).cookie(session))
				.andExpect(status().isBadRequest()); // no-op merger is rejected, not silently "applied"
	}
}
