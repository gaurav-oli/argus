package com.argus.calendar;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.argus.calendar.QuietPeriodStatus.Status;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Quiet-period classification + trading-day counting (Story 5.3). */
class EarningsQuietPeriodServiceTest {

	private final CalendarEventRepository events = mock(CalendarEventRepository.class);
	private final EarningsQuietPeriodService service = new EarningsQuietPeriodService(events);

	// A fixed Monday anchors the weekday math deterministically.
	private static final LocalDate MON = LocalDate.of(2026, 7, 6);

	@Test
	void countsWeekdaysOnly() {
		assertEquals(0, EarningsQuietPeriodService.tradingDaysUntil(MON, MON));        // same day
		assertEquals(1, EarningsQuietPeriodService.tradingDaysUntil(MON, MON.plusDays(1))); // Tue
		assertEquals(2, EarningsQuietPeriodService.tradingDaysUntil(MON, MON.plusDays(2))); // Wed
		// Fri → next Mon skips the weekend: only Monday counts.
		assertEquals(1, EarningsQuietPeriodService.tradingDaysUntil(MON.plusDays(4), MON.plusDays(7)));
	}

	@Test
	void quietWithinTwoTradingDays() {
		assertEquals(Status.QUIET, EarningsQuietPeriodService.classify(MON, MON).status());        // today
		assertEquals(Status.QUIET, EarningsQuietPeriodService.classify(MON, MON.plusDays(1)).status());
		assertEquals(Status.QUIET, EarningsQuietPeriodService.classify(MON, MON.plusDays(2)).status());
	}

	@Test
	void noteWhenWithinFiveCalendarDaysButOutsideQuiet() {
		// Thu = 3 trading days (not quiet), 3 calendar days (<= 5) ⇒ NOTE.
		assertEquals(Status.NOTE, EarningsQuietPeriodService.classify(MON, MON.plusDays(3)).status());
	}

	@Test
	void clearWhenEarningsIsFarOut() {
		// Next Monday: 5 trading days and 7 calendar days ⇒ neither quiet nor note.
		assertEquals(Status.CLEAR, EarningsQuietPeriodService.classify(MON, MON.plusDays(7)).status());
	}

	@Test
	void statusForReturnsClearWhenNoUpcomingEarnings() {
		when(events.findByTickerAndTypeAndEventDateBetweenOrderByEventDateAsc(
				eq("AAPL"), eq(CalendarEventType.EARNINGS), any(), any())).thenReturn(List.of());
		assertEquals(Status.CLEAR, service.statusFor("AAPL").status());
	}

	@Test
	void statusForNormalizesTickerAndReadsTheNextEarnings() {
		LocalDate soon = LocalDate.now(java.time.ZoneId.of("America/Toronto")).plusDays(1);
		when(events.findByTickerAndTypeAndEventDateBetweenOrderByEventDateAsc(
				eq("AAPL"), eq(CalendarEventType.EARNINGS), any(), any()))
				.thenReturn(List.of(new CalendarEvent(CalendarEventType.EARNINGS, "AAPL", "AAPL earnings",
						soon, "finnhub-earnings", "e1")));
		// 1 day out is at most 1 trading day ⇒ QUIET regardless of which weekday "today" is.
		assertEquals(Status.QUIET, service.statusFor("  aapl ").status());
	}
}
