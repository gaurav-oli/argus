package com.argus.calendar;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.argus.TestcontainersConfiguration;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * {@link CalendarEvent} persistence against real Postgres (Story 5.1): enum-text mapping, dedup
 * natural key, and the date-range + ticker/type finders that drive alerts and the quiet period.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class CalendarEventRepositoryIntegrationTest {

	@Autowired
	CalendarEventRepository repo;

	@BeforeEach
	void clean() {
		repo.deleteAll();
	}

	@Test
	void persistsAndDedupsByNaturalKey() {
		repo.save(new CalendarEvent(CalendarEventType.EARNINGS, "AAPL", "AAPL earnings",
				LocalDate.of(2026, 7, 30), "finnhub-earnings", "EARNINGS:AAPL:2026-07-30"));

		assertTrue(repo.existsBySourceAndExternalId("finnhub-earnings", "EARNINGS:AAPL:2026-07-30"));
		assertFalse(repo.existsBySourceAndExternalId("finnhub-earnings", "EARNINGS:AAPL:2026-08-01"));
	}

	@Test
	void rangeFinderReturnsEventsInWindowSoonestFirst() {
		repo.save(event("AAPL", LocalDate.of(2026, 7, 10), "a"));
		repo.save(event("MSFT", LocalDate.of(2026, 7, 5), "b"));
		repo.save(event("NVDA", LocalDate.of(2026, 9, 1), "c")); // outside window

		List<CalendarEvent> window = repo.findByEventDateBetweenOrderByEventDateAsc(
				LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31));

		assertEquals(2, window.size());
		assertEquals("MSFT", window.get(0).getTicker(), "soonest first");
		assertEquals("AAPL", window.get(1).getTicker());
	}

	@Test
	void quietPeriodFinderScopesToTickerAndType() {
		repo.save(event("AAPL", LocalDate.of(2026, 7, 8), "a"));
		repo.save(new CalendarEvent(CalendarEventType.FED, null, "FOMC", LocalDate.of(2026, 7, 9),
				"fed-rss", "fed-1"));

		List<CalendarEvent> hits = repo.findByTickerAndTypeAndEventDateBetweenOrderByEventDateAsc(
				"AAPL", CalendarEventType.EARNINGS, LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31));

		assertEquals(1, hits.size());
		assertEquals(CalendarEventType.EARNINGS, hits.get(0).getType());
	}

	private static CalendarEvent event(String ticker, LocalDate date, String id) {
		return new CalendarEvent(CalendarEventType.EARNINGS, ticker, ticker + " earnings", date,
				"finnhub-earnings", id);
	}
}
