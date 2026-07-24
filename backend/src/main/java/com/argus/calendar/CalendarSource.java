package com.argus.calendar;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;

/**
 * A pluggable upstream calendar provider for Agent 7 (Story 5.1) — Finnhub earnings, Fed RSS, etc.
 * Implementations are key-gated where required and absent otherwise. Fetch failures are swallowed
 * and surfaced as an empty list, so one flaky source never breaks the daily run.
 */
public interface CalendarSource {

	/** Stable source name; also the {@code source} column and dedup namespace. */
	String name();

	/**
	 * Fetch upcoming events. {@code heldTickers} scopes per-holding sources (earnings); macro sources
	 * (Fed) ignore it. Never throws — returns an empty list on failure.
	 */
	List<RawEvent> fetch(Collection<String> heldTickers);

	/**
	 * A raw event before persistence. {@code externalId} is the source's stable id (for dedup).
	 * {@code epsActual}/{@code epsEstimate}/{@code epsSurprisePercent} are only ever non-null for
	 * already-reported {@code EARNINGS} rows from {@link FinnhubEarningsSource}; every other source
	 * passes null for all three.
	 */
	record RawEvent(CalendarEventType type, String ticker, String title, LocalDate eventDate,
			String source, String externalId, Double epsActual, Double epsEstimate, Double epsSurprisePercent) {

		/** Convenience for sources with no earnings-result data (Fed, IPO). */
		public RawEvent(CalendarEventType type, String ticker, String title, LocalDate eventDate,
				String source, String externalId) {
			this(type, ticker, title, eventDate, source, externalId, null, null, null);
		}
	}
}
