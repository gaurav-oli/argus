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

	@Test
	void matchesNonUsCentralBanks() {
		// Coverage that was missing before this list was widened past US-only policy — a story with
		// zero US actors in it must still register as macro-relevant.
		assertTrue(tagger.isMacroRelevant("Bank of England holds rates steady", null));
		assertTrue(tagger.isMacroRelevant("ECB signals further cuts", null));
		assertTrue(tagger.isMacroRelevant("Reserve Bank of India intervenes to defend the rupee", null));
		assertTrue(tagger.isMacroRelevant("Swiss National Bank surprises markets with SNB move", null));
	}

	@Test
	void matchesNonUsCurrencies() {
		assertTrue(tagger.isMacroRelevant("Yuan weakens sharply against the dollar", null));
		assertTrue(tagger.isMacroRelevant("Sterling slides on Brexit fears", null));
	}

	@Test
	void matchesGlobalGeopoliticalCrisisTerms() {
		assertTrue(tagger.isMacroRelevant("Ceasefire announced after weeks of fighting", null));
		assertTrue(tagger.isMacroRelevant("Military coup shocks investors", null));
		assertTrue(tagger.isMacroRelevant("New embargo imposed on exports", null));
		assertTrue(tagger.isMacroRelevant("Troops launch invasion at dawn", null));
	}

	@Test
	void matchesGlobalBodiesAndBlocs() {
		assertTrue(tagger.isMacroRelevant("NATO leaders meet amid tensions", null));
		assertTrue(tagger.isMacroRelevant("G20 finance ministers agree on framework", null));
		assertTrue(tagger.isMacroRelevant("OPEC cuts production targets", null));
	}

	@Test
	void nonUsMacroStoryWithNoTrumpMentionStillMatches() {
		// The exact shape of story the user flagged as missing: a real-world macro/political event with
		// zero connection to Trump or US policy specifically (Bank of Japan currency intervention).
		assertTrue(tagger.isMacroRelevant("Bank of Japan intervenes as yen hits multi-decade low", null));
	}
}
