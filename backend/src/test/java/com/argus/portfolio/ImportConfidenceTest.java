package com.argus.portfolio;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The gate deciding whether a statement parse is trustworthy enough to apply automatically, or
 * needs the heavier adaptive LLM pipeline — one rule, whether the bank is new, changed, or just
 * poorly handled by the fast parser (no separate "known bank" special case). */
class ImportConfidenceTest {

	private static ParsedHolding holding(String ticker, String costBasis, boolean needsReview) {
		return new ParsedHolding(ticker, null, BigDecimal.TEN, new BigDecimal(costBasis), "USD",
				LocalDate.now(), "Cash", needsReview, List.of());
	}

	@Test
	void noHoldingsIsNotConfident() {
		var result = new StatementParser.ParseResult(List.of(), List.of(), List.of(), null);

		var verdict = ImportConfidence.assess(result, "irrelevant text");

		assertFalse(verdict.confident());
		assertTrue(verdict.reason().contains("no holdings"), verdict.reason());
	}

	@Test
	void aParserFailureMessageIsNotConfident() {
		var result = new StatementParser.ParseResult(List.of(), List.of(), List.of(), "No holdings could be read");

		var verdict = ImportConfidence.assess(result, "text");

		assertFalse(verdict.confident());
	}

	@Test
	void anyNeedsReviewFlagIsNotConfident() {
		var result = new StatementParser.ParseResult(List.of(holding("AAPL", "1000", true)), List.of(), List.of(), null);

		var verdict = ImportConfidence.assess(result, "text with no total line");

		assertFalse(verdict.confident());
		assertTrue(verdict.reason().contains("need review"), verdict.reason());
	}

	@Test
	void cleanHoldingsWithNoStatedTotalToCheckAgainstIsConfident() {
		var result = new StatementParser.ParseResult(List.of(holding("AAPL", "1000", false)), List.of(), List.of(), null);

		var verdict = ImportConfidence.assess(result, "statement text with no total anywhere in it");

		assertTrue(verdict.confident());
		assertNull(verdict.reason());
	}

	@Test
	void extractedTotalMatchingTheStatedTotalIsConfident() {
		var result = new StatementParser.ParseResult(List.of(holding("AAPL", "1000.00", false)), List.of(), List.of(), null);

		var verdict = ImportConfidence.assess(result, "Total Portfolio Value: 1,000.00");

		assertTrue(verdict.confident());
	}

	@Test
	void extractedTotalFarBelowTheStatedTotalIsNotConfident() {
		// Extracted 1,000 but the statement says the real total is 10,000 — clearly something's missing.
		var result = new StatementParser.ParseResult(List.of(holding("AAPL", "1000.00", false)), List.of(), List.of(), null);

		var verdict = ImportConfidence.assess(result, "Total Account Value: 10,000.00");

		assertFalse(verdict.confident());
		assertTrue(verdict.reason().contains("doesn't match"), verdict.reason());
	}

	@Test
	void aSmallDriftWithinToleranceIsStillConfident() {
		// FX/rounding-sized gap (within 15%), not a real miss.
		var result = new StatementParser.ParseResult(List.of(holding("AAPL", "950.00", false)), List.of(), List.of(), null);

		var verdict = ImportConfidence.assess(result, "Total Portfolio Value: 1,000.00");

		assertTrue(verdict.confident());
	}

	@Test
	void cashBalancesCountTowardTheExtractedTotalToo() {
		ParsedCash cash = new ParsedCash("Cash", "USD", new BigDecimal("9000.00"));
		var result = new StatementParser.ParseResult(List.of(holding("AAPL", "1000.00", false)), List.of(cash), List.of(), null);

		var verdict = ImportConfidence.assess(result, "Total Portfolio Value: 10,000.00");

		assertTrue(verdict.confident());
	}

	@Test
	void statedTotalParsesVariousRealPhrasings() {
		assertEqualsBigDecimal("1,234.56", ImportConfidence.statedTotal("Total Value: 1,234.56"));
		assertEqualsBigDecimal("1234.56", ImportConfidence.statedTotal("TOTAL MARKET VALUE $1234.56"));
		assertEqualsBigDecimal("999.00", ImportConfidence.statedTotal("Total Assets: 999.00 CAD"));
		assertNull(ImportConfidence.statedTotal("nothing resembling a total here"));
		assertNull(ImportConfidence.statedTotal(null));
	}

	private static void assertEqualsBigDecimal(String expected, BigDecimal actual) {
		org.junit.jupiter.api.Assertions.assertEquals(0, new BigDecimal(expected.replace(",", "")).compareTo(actual));
	}
}
