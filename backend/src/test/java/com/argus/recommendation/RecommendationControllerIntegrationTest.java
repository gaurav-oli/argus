package com.argus.recommendation;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.argus.TestcontainersConfiguration;
import jakarta.servlet.http.Cookie;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/** The /api/recommendations endpoints behind the Forecast Card UI (Stories 6.3/6.7). */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class RecommendationControllerIntegrationTest {

	@Autowired
	MockMvc mockMvc;

	@Autowired
	RecommendationService recommendations;

	@Autowired
	RecommendationRepository repo;

	@Autowired
	com.argus.security.AppCredentialRepository credentials;

	@Autowired
	TradeDecisionRepository decisions;

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

	@Test
	void requiresSession() throws Exception {
		mockMvc.perform(get("/api/recommendations")).andExpect(status().isUnauthorized());
	}

	@Test
	void listReturnsCardsWithDiagnosticAndBadge() throws Exception {
		Cookie session = login();
		recommendations.create("AAPL", List.of(
				new AgentSignal("agent-1-news", SignalDirection.BULLISH, 3, "good"),
				new AgentSignal("agent-7-calendar", SignalDirection.BEARISH, 1, "earnings")), null, "3 months");

		mockMvc.perform(get("/api/recommendations").cookie(session))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[0].ticker").value("AAPL"))
				.andExpect(jsonPath("$[0].direction").value("BULLISH"))
				.andExpect(jsonPath("$[0].badge").value("UNVALIDATED")) // fresh agent = SHADOW
				.andExpect(jsonPath("$[0].blackSwanActive").value(false))
				.andExpect(jsonPath("$[0].signals.length()").value(2));
	}

	@Test
	void decisionMarksTheRecommendationTaken() throws Exception {
		Cookie session = login();
		Recommendation rec = recommendations.create("AAPL",
				List.of(new AgentSignal("agent-1-news", SignalDirection.BULLISH, 2, "good")), null, null);

		mockMvc.perform(post("/api/recommendations/" + rec.getId() + "/decision")
						.cookie(session).contentType(MediaType.APPLICATION_JSON)
						.content("{\"decision\":\"TAKEN\",\"reasoning\":\"I agree\"}"))
				.andExpect(status().isOk());

		mockMvc.perform(get("/api/recommendations").cookie(session))
				.andExpect(jsonPath("$[0].status").value("TAKEN"));
	}
}
