package com.argus.portfolio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.argus.common.BadRequestException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Unit tests for the deterministic statement parser (Story 3.1, FR-1) — no Spring context. */
class StatementParserTest {

	private final StatementParser parser = new StatementParser();

	@Test
	void parsesAFullyPopulatedHoldingRow() {
		byte[] pdf = PdfFixtures.withLines(List.of(
				"Holdings Statement",
				"AAPL 100 150.25 USD 2023-01-15"));

		StatementParser.ParseResult result = parser.parse(pdf);

		assertNull(result.message());
		assertEquals(1, result.holdings().size());
		ParsedHolding aapl = result.holdings().get(0);
		assertEquals("AAPL", aapl.ticker());
		assertEquals(0, aapl.shares().compareTo(new BigDecimal("100")));
		assertEquals(0, aapl.costBasis().compareTo(new BigDecimal("150.25")));
		assertEquals("USD", aapl.costBasisCurrency());
		assertEquals(LocalDate.of(2023, 1, 15), aapl.acquisitionDate());
		assertFalse(aapl.needsReview());
		assertTrue(aapl.issues().isEmpty());
	}

	@Test
	void flagsAPartiallyParsedRowInsteadOfDroppingIt() {
		// TD has shares + a date but no cost basis → kept and flagged, never silently dropped.
		List<ParsedHolding> holdings = parser.parse(PdfFixtures.withLines(List.of("TD 25 2024-03-10")))
				.holdings();

		assertEquals(1, holdings.size());
		ParsedHolding td = holdings.get(0);
		assertEquals("TD", td.ticker());
		assertEquals(0, td.shares().compareTo(new BigDecimal("25")));
		assertEquals(LocalDate.of(2024, 3, 10), td.acquisitionDate());
		assertNull(td.costBasis());
		assertTrue(td.needsReview());
		assertTrue(td.issues().stream().anyMatch(i -> i.startsWith("costBasis")));
	}

	@Test
	void doesNotMistakeDateDigitsForMoney() {
		// Regression: the date 2024-03-10 must not be read as a 2024 cost basis.
		ParsedHolding td = parser.parse(PdfFixtures.withLines(List.of("TD 25 2024-03-10")))
				.holdings().get(0);
		assertNull(td.costBasis());
	}

	@Test
	void parsesValuesOverAThousandWithoutSeparators() {
		// Regression: 12345.67 must NOT split into 123 + 45.67, and 1500 must stay 1500.
		ParsedHolding h = parser.parse(PdfFixtures.withLines(List.of("AAPL 1500 12345.67 USD 2023-01-15")))
				.holdings().get(0);
		assertEquals(0, h.shares().compareTo(new BigDecimal("1500")));
		assertEquals(0, h.costBasis().compareTo(new BigDecimal("12345.67")));
		assertFalse(h.needsReview());
	}

	@Test
	void parsesThousandsSeparatedValues() {
		ParsedHolding h = parser.parse(PdfFixtures.withLines(List.of("AAPL 1,500 12,345.67 USD 2023-01-15")))
				.holdings().get(0);
		assertEquals(0, h.shares().compareTo(new BigDecimal("1500")));
		assertEquals(0, h.costBasis().compareTo(new BigDecimal("12345.67")));
	}

	@Test
	void stripsNonIsoDateDigitsFromTheNumberScan() {
		// 01/15/2023 must not leak 2023 into the cost-basis slot; the row is flagged (no silent drop).
		ParsedHolding h = parser.parse(PdfFixtures.withLines(List.of("TD 25 01/15/2023"))).holdings().get(0);
		assertEquals(0, h.shares().compareTo(new BigDecimal("25")));
		assertNull(h.costBasis());
		assertNull(h.acquisitionDate()); // non-ISO format isn't parsed into a value
		assertTrue(h.needsReview());
	}

	@Test
	void flagsNonPositiveSharesAsNeedingReview() {
		ParsedHolding h = parser.parse(PdfFixtures.withLines(List.of("AAPL 0 150.25 USD 2023-01-15")))
				.holdings().get(0);
		assertTrue(h.needsReview());
		assertTrue(h.issues().stream().anyMatch(i -> i.startsWith("shares")));
	}

	@Test
	void skipsSummaryAndHeaderLines() {
		byte[] pdf = PdfFixtures.withLines(List.of(
				"Account Summary",
				"TOTAL 12345.67 USD",
				"CASH 1000.00",
				"AAPL 100 150.25 USD 2023-01-15"));

		List<ParsedHolding> holdings = parser.parse(pdf).holdings();

		assertEquals(1, holdings.size());
		assertEquals("AAPL", holdings.get(0).ticker());
	}

	@Test
	void returnsAMessageWhenNoHoldingsFound() {
		StatementParser.ParseResult result = parser.parse(
				PdfFixtures.withLines(List.of("This statement has no holdings table.")));

		assertTrue(result.holdings().isEmpty());
		assertNotNull(result.message());
		assertFalse(result.message().isBlank());
	}

	@Test
	void rejectsBytesThatAreNotAPdf() {
		assertThrows(BadRequestException.class, () -> parser.parse("not a pdf".getBytes()));
	}
}
