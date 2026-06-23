package com.argus.intelligence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.argus.TestcontainersConfiguration;
import com.argus.agent.EventEnvelope;
import com.argus.model.ModelGateway;
import com.argus.model.ModelTier;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Sentiment stage against real Postgres (Story 4.2): the model reply round-trips through the agent
 * into the {@code sentiment_label} (enum text) and {@code numeric(4,3)} score/relevance columns.
 * The model is mocked at the gateway boundary so the scores are deterministic.
 */
@SpringBootTest
@ActiveProfiles("dev")
@Import(TestcontainersConfiguration.class)
class NewsSentimentIntegrationTest {

	@Autowired
	NewsArticleRepository articles;

	@Autowired
	NewsSentimentAgent agent;

	@MockitoBean
	ModelGateway gateway;

	@Test
	void scoresArticleAndPersistsSentimentColumns() {
		when(gateway.generate(anyString(), eq(ModelTier.SMALL)))
				.thenReturn("{\"sentiment\":\"BULLISH\",\"score\":0.7,\"relevance\":0.6}");
		NewsArticle saved = articles.save(new NewsArticle("finnhub", "int-1", "http://x",
				"AAPL surges on earnings", "Strong quarter", Instant.now(), new String[] {"AAPL"}));

		agent.handle(new EventEnvelope("e1", "news.article.ingested", Instant.now(), 1,
				Map.of("articleId", saved.getId())));

		NewsArticle reloaded = articles.findById(saved.getId()).orElseThrow();
		assertEquals(SentimentLabel.BULLISH, reloaded.getSentimentLabel());
		assertEquals(0, reloaded.getSentimentScore().compareTo(new BigDecimal("0.700")));
		assertEquals(0, reloaded.getRelevanceScore().compareTo(new BigDecimal("0.600")));
		assertNotNull(reloaded.getAnalyzedAt());
	}
}
