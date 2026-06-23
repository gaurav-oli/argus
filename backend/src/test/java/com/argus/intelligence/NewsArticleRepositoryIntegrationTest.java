package com.argus.intelligence;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.argus.TestcontainersConfiguration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * Verifies the {@link NewsArticle} persistence layer against real Postgres (Story 4.1) — the
 * {@code text[]} ticker-array mapping round-trips and the dedup natural-key check works.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class NewsArticleRepositoryIntegrationTest {

	@Autowired
	NewsArticleRepository repo;

	@Test
	void persistsAndReadsBackTheTickerArray() {
		NewsArticle saved = repo.save(new NewsArticle("finnhub", "abc-1", "http://x", "AAPL beats",
				"summary", Instant.parse("2026-06-23T12:00:00Z"), new String[] {"AAPL", "MSFT"}));

		NewsArticle reloaded = repo.findById(saved.getId()).orElseThrow();
		assertArrayEquals(new String[] {"AAPL", "MSFT"}, reloaded.getTickers());
	}

	@Test
	void persistsAnEmptyTickerArray() {
		NewsArticle saved = repo.save(new NewsArticle("gdelt", "http://news/1", null, "Market wrap",
				null, Instant.parse("2026-06-23T12:00:00Z"), new String[0]));

		NewsArticle reloaded = repo.findById(saved.getId()).orElseThrow();
		assertArrayEquals(new String[0], reloaded.getTickers());
	}

	@Test
	void dedupNaturalKeyCheck() {
		repo.save(new NewsArticle("finnhub", "dup-1", "http://x", "headline", null,
				Instant.now(), new String[0]));

		assertTrue(repo.existsBySourceAndExternalId("finnhub", "dup-1"));
		assertFalse(repo.existsBySourceAndExternalId("finnhub", "missing"));
		assertFalse(repo.existsBySourceAndExternalId("gdelt", "dup-1"));
	}
}
