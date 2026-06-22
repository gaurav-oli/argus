package com.argus.portfolio;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.argus.TestcontainersConfiguration;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import java.util.List;
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
import org.springframework.test.web.servlet.MockMvc;

/** End-to-end portfolio PDF import: upload → preview → confirm → list (Story 3.1, FR-1). */
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
	PortfolioImportRepository imports;

	@Autowired
	com.argus.security.AppCredentialRepository pinCredentials;

	private final ObjectMapper json = new ObjectMapper();

	@BeforeEach
	void reset() {
		positions.deleteAll();
		imports.deleteAll();
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
}
