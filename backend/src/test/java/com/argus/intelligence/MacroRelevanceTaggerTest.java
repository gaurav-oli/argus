package com.argus.intelligence;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Macro/political keyword matching for Agent 8 — pure, no Spring. */
class MacroRelevanceTaggerTest {

	private final MacroRelevanceTagger tagger = new MacroRelevanceTagger();

	@Test
	void matchesTrumpMention() {
		assertTrue(tagger.isMacroRelevant("Trump announces new tariffs on Chinese imports", null));
	}

	@Test
	void matchesTariffInSummaryEvenIfNotInHeadline() {
		assertTrue(tagger.isMacroRelevant("Markets react", "New tariffs take effect next week"));
	}

	@Test
	void matchesFederalReserveAndFomc() {
		assertTrue(tagger.isMacroRelevant("Federal Reserve holds rates steady", null));
		assertTrue(tagger.isMacroRelevant("FOMC minutes released", null));
	}

	@Test
	void matchIsCaseInsensitive() {
		assertTrue(tagger.isMacroRelevant("TRUMP threatens new tariffs", null));
	}

	@Test
	void matchesMultiWordPhraseTradeWar() {
		assertTrue(tagger.isMacroRelevant("Escalating trade war rattles markets", null));
	}

	@Test
	void ordinaryCompanyHeadlineIsNotMacro() {
		assertFalse(tagger.isMacroRelevant("AAPL jumps on strong earnings", "iPhone sales beat estimates"));
	}

	@Test
	void bareFedIsNotMatchedToAvoidFalsePositives() {
		// "fed" alone (not "Federal Reserve"/"FOMC") is excluded — "investors fed up with volatility"
		// shouldn't be tagged as Fed policy news.
		assertFalse(tagger.isMacroRelevant("Investors fed up with volatility", null));
	}

	@Test
	void handlesNullSummaryGracefully() {
		assertFalse(tagger.isMacroRelevant("Quiet trading day", null));
	}
}
