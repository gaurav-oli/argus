package com.argus.strategy;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.StringReader;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The corpus's definition column carries the papers' own prose, so quoting has to survive intact. */
class CsvTest {

	private static List<List<String>> parse(String s) {
		return Csv.parse(new StringReader(s));
	}

	@Test
	void parsesPlainRows() {
		assertEquals(List.of(List.of("a", "b"), List.of("1", "2")), parse("a,b\n1,2"));
	}

	@Test
	void keepsCommasInsideQuotedFields() {
		List<List<String>> rows = parse("x,def\nMom12m,\"return between months t-12 and t-1, equal weighted\"");

		assertEquals("return between months t-12 and t-1, equal weighted", rows.get(1).get(1));
		assertEquals(2, rows.get(1).size());
	}

	@Test
	void unescapesDoubledQuotes() {
		assertEquals("he said \"buy\"", parse("a\n\"he said \"\"buy\"\"\"").get(1).get(0));
	}

	@Test
	void keepsNewlinesInsideQuotedFields() {
		List<List<String>> rows = parse("a,b\n\"line one\nline two\",x");

		assertEquals(2, rows.size());
		assertEquals("line one\nline two", rows.get(1).get(0));
		assertEquals("x", rows.get(1).get(1));
	}

	@Test
	void handlesEmptyFieldsCarriageReturnsAndATrailingRowWithoutNewline() {
		assertEquals(List.of("a", "", "c"), parse("a,,c").get(0));
		assertEquals(List.of(List.of("a", "b"), List.of("1", "2")), parse("a,b\r\n1,2\r\n"));
	}
}
