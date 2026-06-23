package com.argus.calendar;

import com.argus.calendar.QuietPeriodStatus.Status;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The pre-earnings quiet period (Story 5.3, FR-23). Given a ticker, reports whether its next earnings
 * is close enough to suppress a recommendation (QUIET, within 2 trading days) or merely worth noting
 * in the bear scenario (NOTE, within 5 calendar days). Agent 5 (Epic 6) consults this before
 * recommending. Read-only; the classification is a pure function of the dates so it's deterministic.
 *
 * <p>Trading days count weekdays only — market holidays are not modeled in MVP (documented gap,
 * consistent with {@code MarketClock}), so a holiday is treated as a trading day.
 */
@Service
public class EarningsQuietPeriodService {

	static final int QUIET_TRADING_DAYS = 2;
	static final int NOTE_CALENDAR_DAYS = 5;
	private static final int LOOKAHEAD_DAYS = 30;
	private static final ZoneId TORONTO = ZoneId.of("America/Toronto");

	private final CalendarEventRepository events;

	public EarningsQuietPeriodService(CalendarEventRepository events) {
		this.events = events;
	}

	/** The quiet-period posture for {@code ticker} based on its next upcoming earnings. */
	@Transactional(readOnly = true)
	public QuietPeriodStatus statusFor(String ticker) {
		if (ticker == null || ticker.isBlank()) {
			return QuietPeriodStatus.clear();
		}
		LocalDate today = LocalDate.now(TORONTO);
		List<CalendarEvent> upcoming = events.findByTickerAndTypeAndEventDateBetweenOrderByEventDateAsc(
				ticker.trim().toUpperCase(), CalendarEventType.EARNINGS, today, today.plusDays(LOOKAHEAD_DAYS));
		if (upcoming.isEmpty()) {
			return QuietPeriodStatus.clear();
		}
		return classify(today, upcoming.get(0).getEventDate());
	}

	/** Pure classification of an earnings date relative to {@code today} (visible for testing). */
	static QuietPeriodStatus classify(LocalDate today, LocalDate earnings) {
		int tradingDays = tradingDaysUntil(today, earnings);
		long calendarDays = ChronoUnit.DAYS.between(today, earnings);
		Status status;
		if (tradingDays <= QUIET_TRADING_DAYS) {
			status = Status.QUIET;
		} else if (calendarDays <= NOTE_CALENDAR_DAYS) {
			status = Status.NOTE;
		} else {
			status = Status.CLEAR;
		}
		return new QuietPeriodStatus(status, earnings, tradingDays);
	}

	/** Business days strictly after {@code today} up to and including {@code earnings} (0 if same day). */
	static int tradingDaysUntil(LocalDate today, LocalDate earnings) {
		int count = 0;
		for (LocalDate d = today.plusDays(1); !d.isAfter(earnings); d = d.plusDays(1)) {
			if (d.getDayOfWeek() != DayOfWeek.SATURDAY && d.getDayOfWeek() != DayOfWeek.SUNDAY) {
				count++;
			}
		}
		return count;
	}
}
