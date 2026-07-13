package com.argus.calendar;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.argus.calendar.CalendarSource.RawEvent;
import com.argus.intelligence.KnownUniverse;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Agent 7 ingestion: dedup (in-batch + stored), source-failure isolation, persistence (Story 5.1). */
class Agent7CalendarServiceTest {

	private final CalendarEventRepository events = mock(CalendarEventRepository.class);
	private final KnownUniverse universe = mock(KnownUniverse.class);

	private static RawEvent earnings(String ticker, String date) {
		LocalDate d = LocalDate.parse(date);
		return new RawEvent(CalendarEventType.EARNINGS, ticker, ticker + " earnings", d,
				"finnhub-earnings", "EARNINGS:" + ticker + ":" + date);
	}

	private void heldAapl() {
		when(universe.knownTickers()).thenReturn(Set.of("AAPL"));
	}

	private Agent7CalendarService service(List<CalendarSource> sources) {
		return new Agent7CalendarService(sources, events, universe);
	}

	@Test
	void storesNewEvents() {
		heldAapl();
		CalendarSource src = mock(CalendarSource.class);
		when(src.name()).thenReturn("finnhub-earnings");
		when(src.fetch(any())).thenReturn(List.of(
				earnings("AAPL", "2026-07-30"), earnings("MSFT", "2026-07-22")));
		when(events.existsBySourceAndExternalId(anyString(), anyString())).thenReturn(false);

		assertEquals(2, service(List.of(src)).ingestOnce());
		verify(events, times(2)).save(any(CalendarEvent.class));
	}

	@Test
	void skipsAlreadyStoredEvents() {
		heldAapl();
		CalendarSource src = mock(CalendarSource.class);
		when(src.name()).thenReturn("finnhub-earnings");
		when(src.fetch(any())).thenReturn(List.of(earnings("AAPL", "2026-07-30")));
		when(events.existsBySourceAndExternalId("finnhub-earnings", "EARNINGS:AAPL:2026-07-30"))
				.thenReturn(true);

		assertEquals(0, service(List.of(src)).ingestOnce());
		verify(events, never()).save(any());
	}

	@Test
	void deduplicatesWithinOneRun() {
		heldAapl();
		CalendarSource src = mock(CalendarSource.class);
		when(src.name()).thenReturn("finnhub-earnings");
		when(src.fetch(any())).thenReturn(List.of(
				earnings("AAPL", "2026-07-30"), earnings("AAPL", "2026-07-30")));
		when(events.existsBySourceAndExternalId(anyString(), anyString())).thenReturn(false);

		assertEquals(1, service(List.of(src)).ingestOnce());
		verify(events, times(1)).save(any());
	}

	@Test
	void oneFailingSourceDoesNotAbortTheRun() {
		heldAapl();
		CalendarSource bad = mock(CalendarSource.class);
		when(bad.name()).thenReturn("bad");
		when(bad.fetch(any())).thenThrow(new RuntimeException("boom"));
		CalendarSource good = mock(CalendarSource.class);
		when(good.name()).thenReturn("finnhub-earnings");
		when(good.fetch(any())).thenReturn(List.of(earnings("AAPL", "2026-07-30")));
		when(events.existsBySourceAndExternalId(anyString(), anyString())).thenReturn(false);

		assertEquals(1, service(List.of(bad, good)).ingestOnce());
	}

	@Test
	void passesHeldTickersToSources() {
		heldAapl();
		CalendarSource src = mock(CalendarSource.class);
		lenient().when(src.name()).thenReturn("finnhub-earnings");
		when(src.fetch(eq(java.util.Set.of("AAPL")))).thenReturn(List.of());

		service(List.of(src)).ingestOnce();

		verify(src).fetch(eq(java.util.Set.of("AAPL")));
	}
}
