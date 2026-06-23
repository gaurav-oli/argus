package com.argus.intelligence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Ticker relevance tagging for Agent 1 (Story 4.1) — pure, no Spring. */
class TickerRelevanceTaggerTest {

	private final TickerRelevanceTagger tagger = new TickerRelevanceTagger();

	private static RawArticle article(String headline, String summary, List<String> queryTickers) {
		return new RawArticle("test", "id-1", "http://x", headline, summary, Instant.now(), queryTickers);
	}

	@Test
	void keepsSourceQueryTickerWhenHeld() {
		RawArticle a = article("Some headline", null, List.of("AAPL"));
		assertEquals(List.of("AAPL"), tagger.tag(a, Set.of("AAPL", "MSFT")));
	}

	@Test
	void dropsQueryTickerWeDoNotHold() {
		RawArticle a = article("Some headline", null, List.of("TSLA"));
		assertTrue(tagger.tag(a, Set.of("AAPL")).isEmpty());
	}

	@Test
	void matchesHeldTickerMentionedInText() {
		RawArticle a = article("MSFT beats earnings", "Strong cloud growth", List.of());
		assertEquals(List.of("MSFT"), tagger.tag(a, Set.of("AAPL", "MSFT")));
	}

	@Test
	void matchIsCaseInsensitive() {
		RawArticle hit = article("shares of aapl rose", null, List.of());
		assertEquals(List.of("AAPL"), tagger.tag(hit, Set.of("AAPL")));
	}

	@Test
	void matchIsWholeWordOnly() {
		// "AAPL" must not match inside "AAPLE".
		RawArticle miss = article("the AAPLE pie company", null, List.of());
		assertTrue(tagger.tag(miss, Set.of("AAPL")).isEmpty());
	}

	@Test
	void deduplicatesAcrossQueryAndTextMatch() {
		RawArticle a = article("AAPL up on results", null, List.of("AAPL"));
		assertEquals(List.of("AAPL"), tagger.tag(a, Set.of("AAPL")));
	}

	@Test
	void returnsEmptyWhenNothingRelevant() {
		RawArticle a = article("General market wrap", "Indices mixed", List.of());
		assertTrue(tagger.tag(a, Set.of("AAPL", "MSFT")).isEmpty());
	}
}
