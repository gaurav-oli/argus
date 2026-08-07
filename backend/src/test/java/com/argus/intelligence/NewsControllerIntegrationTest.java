package com.argus.intelligence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import com.argus.TestcontainersConfiguration;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * Curated news queue endpoints against real Postgres: {@code /queue} returns every ready card
 * (not just the single highest-impact one {@code /next} returns), and {@code done} tolerates a
 * card that's already gone (Story: news carousel — a carousel can hold a stale card id after
 * background pruning removes it server-side).
 */
@SpringBootTest
@ActiveProfiles("dev")
@Import(TestcontainersConfiguration.class)
class NewsControllerIntegrationTest {

	@Autowired
	NewsArticleRepository articles;

	@Autowired
	NewsCardRepository cards;

	@Autowired
	NewsController controller;

	@BeforeEach
	void clean() {
		cards.deleteAll();
		articles.deleteAll();
	}

	private NewsCard readyCard(String headline, double impact) {
		return readyCard(headline, impact, Instant.now());
	}

	private NewsCard readyCard(String headline, double impact, Instant publishedAt) {
		NewsArticle article = articles.save(new NewsArticle("finnhub", "ext-" + headline, "http://x",
				headline, "snippet", publishedAt, new String[] {"AAPL"}));
		NewsCard card = new NewsCard(article, impact);
		card.summarize("A plain-language paragraph.\n\nKEY TERMS:\nNone", false);
		return cards.save(card);
	}

	@Test
	void queueReturnsEveryReadyCardMostImportantFirst() {
		readyCard("Lower impact story", 0.2);
		readyCard("Higher impact story", 0.9);
		cards.save(new NewsCard(
				articles.save(new NewsArticle("finnhub", "ext-pending", "http://x", "Pending story",
						"snippet", Instant.now(), new String[0])),
				0.5)); // no summarize() call — stays pending

		NewsController.NewsQueue queue = controller.queue();

		assertEquals(2, queue.cards().size(), "only ready (summarized) cards appear in the queue");
		assertEquals("Higher impact story", queue.cards().get(0).headline(), "most important first");
		assertEquals("Lower impact story", queue.cards().get(1).headline());
		assertEquals(1, queue.pending(), "the unsummarized card counts as pending, not ready");
	}

	@Test
	void queueExcludesCardsOlderThanTodayOrYesterday() {
		readyCard("Fresh today", 0.5, Instant.now());
		readyCard("From three days ago", 0.9, Instant.now().minus(java.time.Duration.ofDays(3)));

		NewsController.NewsQueue queue = controller.queue();

		assertEquals(1, queue.cards().size(), "a 3-day-old card must not appear even if higher impact");
		assertEquals("Fresh today", queue.cards().get(0).headline());
	}

	@Test
	void doneOnAnAlreadyDeletedCardDoesNotThrow() {
		NewsCard card = readyCard("Will be pruned first", 0.5);
		cards.deleteById(card.getId());
		cards.flush();

		assertDoesNotThrow(() -> controller.done(card.getId()),
				"a carousel acting on a card the background pruner already removed must not 500");
	}

	@Test
	void doneRemovesTheCardFromTheQueue() {
		NewsCard card = readyCard("Read me", 0.5);

		controller.done(card.getId());

		assertTrue(controller.queue().cards().isEmpty());
	}
}
