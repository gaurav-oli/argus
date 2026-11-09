package com.argus.regime;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.Optional;
import java.util.TreeSet;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Scheduled market-moving releases (strategy rebuild, Phase 4): FOMC decision days and the jobs report.
 *
 * <p>The Fed RSS feed only records statements after they happen, so the forward schedule comes from configuration
 * ({@code argus.macro.fomc-dates}, the Fed's published calendar; extend it when the next year's dates are released).
 * The jobs report is taken as the first Friday of the month, which is right most months (BLS occasionally shifts it).
 * CPI isn't covered: there's no reliable free schedule source wired into the app yet.
 */
@Component
public class MacroEventCalendar {

	public enum Kind { FOMC, JOBS }

	public record Event(LocalDate date, Kind kind) {
	}

	private final TreeSet<LocalDate> fomc = new TreeSet<>();

	public MacroEventCalendar(@Value("${argus.macro.fomc-dates:2026-01-28,2026-03-18,2026-04-29,2026-06-17,2026-07-29,"
			+ "2026-09-16,2026-10-28,2026-12-09}") List<String> fomcDates) {
		for (String d : fomcDates) {
			if (d != null && !d.isBlank()) {
				fomc.add(LocalDate.parse(d.trim()));
			}
		}
	}

	/** The first Friday of {@code date}'s month. */
	static LocalDate jobsDay(LocalDate date) {
		return date.withDayOfMonth(1).with(TemporalAdjusters.nextOrSame(DayOfWeek.FRIDAY));
	}

	/** The release on {@code date}, if any. */
	public Optional<Event> on(LocalDate date) {
		if (fomc.contains(date)) {
			return Optional.of(new Event(date, Kind.FOMC));
		}
		if (jobsDay(date).equals(date)) {
			return Optional.of(new Event(date, Kind.JOBS));
		}
		return Optional.empty();
	}

	/** The release on the next weekday after {@code today}, if any — "is there a big release tomorrow?". */
	public Optional<Event> nextTradingDay(LocalDate today) {
		LocalDate next = today.plusDays(1);
		while (next.getDayOfWeek() == DayOfWeek.SATURDAY || next.getDayOfWeek() == DayOfWeek.SUNDAY) {
			next = next.plusDays(1);
		}
		return on(next);
	}

	/** Every release between {@code from} and {@code to}, inclusive, in date order. */
	public List<Event> between(LocalDate from, LocalDate to) {
		List<Event> out = new java.util.ArrayList<>();
		for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
			on(d).ifPresent(out::add);
		}
		return out;
	}
}
