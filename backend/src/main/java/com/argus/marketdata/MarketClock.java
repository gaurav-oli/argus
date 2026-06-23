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
