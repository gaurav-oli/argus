package com.argus.conversation;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.argus.TestcontainersConfiguration;
import com.argus.recommendation.AgentSignal;
import com.argus.recommendation.Recommendation;
import com.argus.recommendation.RecommendationRepository;
import com.argus.recommendation.RecommendationService;
import com.argus.recommendation.SignalDirection;
import com.argus.recommendation.TradeDecisionRepository;
import jakarta.servlet.http.Cookie;
import java.util.List;
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
 * Ask AI about a recommendation end-to-end (Story 7.1). Runs under the {@code dev} profile so the
 * Model Gateway resolves through the in-memory {@code MockChatModel} (no Ollama) — the full
 * request → context-assembly → gateway → response path is exercised on the laptop. Real
 * {@code gemma4:26b} generation + the ≤15s latency target are verified on the Mac Mini.
 */
@SpringBootTest
@ActiveProfiles("dev")
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class ConversationIntegrationTest {

	@Autowired
	MockMvc mockMvc;

	@Autowired
	RecommendationService recommendations;

	@Autowired
	RecommendationRepository repo;

	@Autowired
	TradeDecisionRepository decisions;

	@Autowired
	com.argus.security.AppCredentialRepository credentials;

	@BeforeEach
	void clean() {
		decisions.deleteAll(); // FK → recommendations; clear children first
		repo.deleteAll();
		credentials.deleteAll(); // shared test DB — start without a PIN so setup returns 201
	}

	private Cookie login() throws Exception {
		mockMvc.perform(post("/api/auth/pin").contentType(MediaType.APPLICATION_JSON).content("{\"pin\":\"1234\"}"))
				.andExpect(status().isCreated());
		return mockMvc.perform(post("/api/auth/login")
						.contentType(MediaType.APPLICATION_JSON).content("{\"pin\":\"1234\"}"))
				.andExpect(status().isOk()).andReturn().getResponse().getCookie("ARGUS_SESSION");
	}

	private Long seedRecommendation() {
		return recommendations.create("AAPL",
				List.of(new AgentSignal("agent-1-news", SignalDirection.BULLISH, 3, "Strong sentiment")),
				null, "3 months").getId();
	}

	private static final String BODY = "{\"messages\":[{\"role\":\"user\",\"content\":\"Why is AAPL bullish?\"}]}";

	@Test
	void requiresSession() throws Exception {
		mockMvc.perform(post("/api/recommendations/1/chat")
						.contentType(MediaType.APPLICATION_JSON).content(BODY))
				.andExpect(status().isUnauthorized());
	}

	@Test
	void returnsAGroundedAssistantMessage() throws Exception {
		Cookie session = login();
		Long id = seedRecommendation();

		mockMvc.perform(post("/api/recommendations/" + id + "/chat")
						.cookie(session).contentType(MediaType.APPLICATION_JSON).content(BODY))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.role").value("assistant"))
				.andExpect(jsonPath("$.content").isNotEmpty());
	}

	@Test
	void unknownRecommendationIs404() throws Exception {
		Cookie session = login();

		mockMvc.perform(post("/api/recommendations/999999/chat")
						.cookie(session).contentType(MediaType.APPLICATION_JSON).content(BODY))
				.andExpect(status().isNotFound());
	}

	@Test
	void emptyThreadIs400() throws Exception {
		Cookie session = login();
		Long id = seedRecommendation();

		mockMvc.perform(post("/api/recommendations/" + id + "/chat")
						.cookie(session).contentType(MediaType.APPLICATION_JSON).content("{\"messages\":[]}"))
				.andExpect(status().isBadRequest());
	}

	@Test
	void blankTrailingQuestionIs400() throws Exception {
		Cookie session = login();
		Long id = seedRecommendation();

		mockMvc.perform(post("/api/recommendations/" + id + "/chat")
						.cookie(session).contentType(MediaType.APPLICATION_JSON)
						.content("{\"messages\":[{\"role\":\"user\",\"content\":\"   \"}]}"))
				.andExpect(status().isBadRequest());
	}

	@Test
	void oversizedMessageIs413() throws Exception {
		Cookie session = login();
		Long id = seedRecommendation();
		String huge = "x".repeat(4_001);
		String body = "{\"messages\":[{\"role\":\"user\",\"content\":\"" + huge + "\"}]}";

		mockMvc.perform(post("/api/recommendations/" + id + "/chat")
						.cookie(session).contentType(MediaType.APPLICATION_JSON).content(body))
				.andExpect(status().isPayloadTooLarge());
	}
}
