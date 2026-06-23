package com.argus.intelligence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import org.junit.jupiter.api.Test;

/** Cashtag symbol extraction (Story 4.4). */
class CashtagExtractorTest {

	private final CashtagExtractor extractor = new CashtagExtractor();

	@Test
	void extractsUpperCasedCashtags() {
		assertEquals(Set.of("TSLA", "GME"), extractor.extract("Frenzy in $TSLA and $gme today"));
	}

	@Test
	void deduplicatesRepeatedMentions() {
		assertEquals(Set.of("ABCD"), extractor.extract("$ABCD soars, $ABCD again, $ABCD"));
	}

	@Test
	void ignoresPlainMoneyAmountsAndLongTokens() {
		assertTrue(extractor.extract("shares cost $5 and $123 not tickers").isEmpty());
		assertTrue(extractor.extract("$TOOLONGSYM is not 1-5 letters").isEmpty());
	}

	@Test
	void emptyOrNullTextYieldsNothing() {
		assertTrue(extractor.extract("").isEmpty());
		assertTrue(extractor.extract(null).isEmpty());
	}
}
