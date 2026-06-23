package com.argus.marketdata;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import org.junit.jupiter.api.Test;

/** Unit tests for the US regular-session clock (Story 3.4) — pure, no Spring. */
class MarketClockTest {

	private final MarketClock clock = new MarketClock();
	private static final ZoneId NY = ZoneId.of("America/New_York");

	private static java.time.Instant nyTime(LocalDate date, int hour, int minute) {
		return ZonedDateTime.of(date, LocalTime.of(hour, minute), NY).toInstant();
	}

	@Test
	void weekdayDuringSessionIsRegularHours() {
		// Thursday 2023-06-15, 14:00 ET.
		assertTrue(clock.isRegularHours(nyTime(LocalDate.of(2023, 6, 15), 14, 0)));
	}

	@Test
	void beforeOpenAndAfterCloseAreAfterHours() {
		assertFalse(clock.isRegularHours(nyTime(LocalDate.of(2023, 6, 15), 9, 0)));   // pre-market
		assertFalse(clock.isRegularHours(nyTime(LocalDate.of(2023, 6, 15), 16, 30))); // post-market
	}

	@Test
	void weekendIsAfterHours() {
		assertFalse(clock.isRegularHours(nyTime(LocalDate.of(2023, 6, 17), 14, 0))); // Saturday
	}

	@Test
	void openBoundaryIsRegularCloseBoundaryIsNot() {
		assertTrue(clock.isRegularHours(nyTime(LocalDate.of(2023, 6, 15), 9, 30)));  // 09:30 inclusive
		assertFalse(clock.isRegularHours(nyTime(LocalDate.of(2023, 6, 15), 16, 0))); // 16:00 exclusive
	}
}
