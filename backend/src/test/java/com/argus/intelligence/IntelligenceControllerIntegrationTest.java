package com.argus.intelligence;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.argus.TestcontainersConfiguration;
import jakarta.servlet.http.Cookie;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The /api/intelligence read endpoints that feed the Intelligence UI (Epic 4): session-gated, and
 * returning news (with sentiment/relevance), source credibility, and stranger alerts in the camelCase
 * shape the frontend consumes.
 */
@SpringBootTest
@ActiveProfiles("dev")
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class IntelligenceControllerIntegrationTest {

	@Autowired
	MockMvc mockMvc;

	@Autowired
	NewsArticleRepository articles;

	@Autowired
	SourceCredibilityService credibility;

	@Autowired
	SourceCredibilityRepository sources;

	@Autowired
	StrangerAlertRepository strangers;

	@BeforeEach
	void clean() {
		strangers.deleteAll();
		articles.deleteAll();
		sources.deleteAll();
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
	void endpointsAreSessionGated() throws Exception {
		mockMvc.perform(get("/api/intelligence/news")).andExpect(status().isUnauthorized());
		mockMvc.perform(get("/api/intelligence/sources")).andExpect(status().isUnauthorized());
		mockMvc.perform(get("/api/intelligence/strangers")).andExpect(status().isUnauthorized());
	}

	@Test
	void returnsNewsSourcesAndStrangersForTheUi() throws Exception {
		Cookie session = login();

		NewsArticle a = new NewsArticle("Reuters", "ic-1", "http://x", "AAPL beats on services",
				"summary", Instant.parse("2026-06-23T12:00:00Z"), new String[] {"AAPL"});
		a.applySentiment(new SentimentAnalysis(SentimentLabel.BULLISH, 0.8, 0.9), Instant.now());
		articles.save(a);

		credibility.register("Reuters");
		strangers.save(new StrangerAlert("ZZZP", new StrangerAssessment(7, 1, 8.0, 82), 6, Instant.now()));

		mockMvc.perform(get("/api/intelligence/news").cookie(session))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[0].headline").value("AAPL beats on services"))
				.andExpect(jsonPath("$[0].source").value("Reuters"))
				.andExpect(jsonPath("$[0].sentimentLabel").value("BULLISH"))
				.andExpect(jsonPath("$[0].tickers[0]").value("AAPL"))
				.andExpect(jsonPath("$[0].analyzed").value(true));

		mockMvc.perform(get("/api/intelligence/sources").cookie(session))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[0].source").value("Reuters"))
				.andExpect(jsonPath("$[0].tier").value("BRONZE")); // freshly registered = 35

		mockMvc.perform(get("/api/intelligence/strangers").cookie(session))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[0].ticker").value("ZZZP"))
				.andExpect(jsonPath("$[0].riskScore").value(82))
				.andExpect(jsonPath("$[0].requiredConsensus").value(6));
	}
}
