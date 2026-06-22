package com.argus.portfolio;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.argus.TestcontainersConfiguration;
import jakarta.servlet.http.Cookie;
import java.util.List;
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
import org.springframework.test.web.servlet.MockMvc;

/**
 * The controller's byte ceiling (Story 3.1, AC #5). A tiny {@code max-file-bytes} forces any real
 * PDF over the limit, exercising the 413 Problem Details path that MockMvc can't trigger through
 * the servlet multipart resolver.
 */
@SpringBootTest(properties = "argus.portfolio.import.max-file-bytes=10")
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class PortfolioImportSizeLimitTest {

	@Autowired
	MockMvc mockMvc;

	@Autowired
	StringRedisTemplate redis;

	@Autowired
	com.argus.security.AppCredentialRepository pinCredentials;

	@BeforeEach
	void reset() {
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
	void oversizeUploadReturns413ProblemDetails() throws Exception {
		Cookie session = login();
		MockMultipartFile file = new MockMultipartFile("file", "statement.pdf",
				MediaType.APPLICATION_PDF_VALUE, PdfFixtures.withLines(List.of("AAPL 100 150.25 USD 2023-01-15")));

		mockMvc.perform(multipart("/api/portfolio/imports").file(file).cookie(session))
				.andExpect(status().isPayloadTooLarge())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
	}
}
