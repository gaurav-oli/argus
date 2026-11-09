package com.argus.regime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class MacroEventCalendarTest {

	private final MacroEventCalendar cal = new MacroEventCalendar(List.of("2026-12-09"));

	@Test
	void jobsReportIsTheFirstFriday() {
		assertEquals(LocalDate.of(2026, 11, 6), MacroEventCalendar.jobsDay(LocalDate.of(2026, 11, 20)));
		assertEquals(LocalDate.of(2026, 5, 1), MacroEventCalendar.jobsDay(LocalDate.of(2026, 5, 15)));
	}

	@Test
	void tomorrowLooksPastTheWeekend() {
		// Thursday 2026-12-03 → Friday 2026-12-04 is the jobs report.
		assertEquals(MacroEventCalendar.Kind.JOBS, cal.nextTradingDay(LocalDate.of(2026, 12, 3)).orElseThrow().kind());
		// Tuesday 2026-12-08 → Wednesday is FOMC.
		assertEquals(MacroEventCalendar.Kind.FOMC, cal.nextTradingDay(LocalDate.of(2026, 12, 8)).orElseThrow().kind());
		assertTrue(cal.nextTradingDay(LocalDate.of(2026, 12, 14)).isEmpty());
	}

	@Test
	void betweenListsEveryRelease() {
		assertEquals(3, cal.between(LocalDate.of(2026, 11, 1), LocalDate.of(2026, 12, 31)).size()); // Nov 6, Dec 4, Dec 9
	}
}
