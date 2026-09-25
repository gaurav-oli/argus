package com.argus.marketdata;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Parsing of Yahoo's chart payload: null-padded sessions, exchange-local dates, live-vs-completed bars. */
class YahooChartClientTest {

	// 2026-09-22 13:30Z, 2026-09-23 13:30Z, 2026-09-24 13:30Z (NY open, gmtoffset -4h → same local date).
	private static final String BODY = """
			{"chart":{"result":[{"meta":{"currency":"USD","symbol":"AAPL","regularMarketPrice":198.0,
			 "regularMarketTime":1790256600,"gmtoffset":-14400},
			 "timestamp":[1790083800,1790170200,1790256600],
			 "indicators":{"quote":[{"open":[100,null,110],"high":[102,null,112],"low":[99,null,109],
			 "close":[101,null,200],"volume":[1000,null,3000]}]}}],"error":null}}""";

	@Test
	void parsesBarsSkippingNullPaddedSessions() {
		var s = YahooChartClient.parse("AAPL", BODY).orElseThrow();

		assertEquals("USD", s.currency());
		assertEquals(2, s.bars().size(), "the null-padded session is dropped");
		assertEquals(LocalDate.of(2026, 9, 22), s.bars().get(0).date());
		assertEquals(0, s.bars().get(0).close().compareTo(new java.math.BigDecimal("101")));
	}

	@Test
	void changeIsMeasuredAgainstTheLastCompletedSessionNotTheFormingOne() {
		var s = YahooChartClient.parse("AAPL", BODY).orElseThrow();

		// live 198 (Sep 24) vs the last bar dated BEFORE Sep 24 (Sep 22 @ 101) — the forming bar (200)
		// must not be used as "yesterday's close".
		assertEquals(LocalDate.of(2026, 9, 24), s.liveDate());
		assertEquals(101.0, s.previousClose().orElseThrow().doubleValue(), 1e-9);
		assertEquals((198.0 - 101.0) / 101.0 * 100.0, s.changePct1d().orElseThrow(), 1e-3);
	}

	@Test
	void malformedOrEmptyPayloadsAreEmptyNotExceptions() {
		assertEquals(Optional.empty(), YahooChartClient.parse("X", "{\"chart\":{\"result\":null,\"error\":{}}}"));
		assertTrue(YahooChartClient.parse("X", "{\"chart\":{\"result\":[{\"meta\":{},\"timestamp\":[]}]}}").isEmpty());
	}
}
