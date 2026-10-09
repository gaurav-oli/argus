package com.argus.marketdata;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import org.springframework.stereotype.Component;

/**
 * US equity regular-session clock (Story 3.4, FR-2). A price is "regular hours" only on a weekday
 * between 09:30 and 16:00 America/New_York (DST-aware); anything else is after-hours. Pure function
 * of the supplied instant — no ambient time — so it's deterministic in tests. Market holidays are
 * not modeled in MVP (documented gap): a holiday reads as regular hours by time-of-day alone.
 */
@Component
public class MarketClock {

	private static final ZoneId NEW_YORK = ZoneId.of("America/New_York");
	private static final LocalTime OPEN = LocalTime.of(9, 30);
	private static final LocalTime CLOSE = LocalTime.of(16, 0);

	private static final LocalTime PRE_MARKET = LocalTime.of(4, 0);
	private static final LocalTime AFTER_HOURS_END = LocalTime.of(20, 0);

	/** US extended session: pre-market 04:00 through after-hours 20:00 ET, weekdays — when prices can still move. */
	public boolean isExtendedHours(Instant at) {
		ZonedDateTime ny = at.atZone(NEW_YORK);
		DayOfWeek day = ny.getDayOfWeek();
		if (day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY) {
			return false;
		}
		LocalTime time = ny.toLocalTime();
		return !time.isBefore(PRE_MARKET) && time.isBefore(AFTER_HOURS_END);
	}

	public boolean isRegularHours(Instant at) {
		ZonedDateTime ny = at.atZone(NEW_YORK);
		DayOfWeek day = ny.getDayOfWeek();
		if (day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY) {
			return false;
		}
		LocalTime time = ny.toLocalTime();
		return !time.isBefore(OPEN) && time.isBefore(CLOSE);
	}
}
