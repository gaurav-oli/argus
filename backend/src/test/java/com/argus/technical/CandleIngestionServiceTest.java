package com.argus.technical;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.argus.intelligence.KnownUniverse;
import com.argus.marketdata.AlphaVantageRest;
import com.argus.marketdata.ListingResolver;
import com.argus.marketdata.YahooChartClient;
import com.argus.notification.NotificationService;
import com.argus.portfolio.LivePortfolioService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Agent 10's candle ingestion. The pipeline produced zero rows for weeks: every ticker's first call
 * asked Alpha Vantage for {@code outputsize=full} (premium-only), so no history ever landed and every
 * later call asked for {@code full} again. These tests pin the fixed behaviour: Yahoo primary, stalest
 * first, no in-progress bars, never {@code full}, per-ticker isolation.
 */
class CandleIngestionServiceTest {

	private final YahooChartClient yahoo = mock(YahooChartClient.class);
	private final AlphaVantageRest alphaVantage = mock(AlphaVantageRest.class);
	private final PriceCandleRepository candles = mock(PriceCandleRepository.class);
	private final KnownUniverse universe = mock(KnownUniverse.class);
	private final LivePortfolioService livePrices = mock(LivePortfolioService.class);
	private final NotificationService notifications = mock(NotificationService.class);
	private final ChartStudyService charts = mock(ChartStudyService.class);
	private final org.springframework.context.ApplicationEventPublisher events = mock(org.springframework.context.ApplicationEventPublisher.class);
	private final TechnicalAnalysisProperties props = new TechnicalAnalysisProperties(400);
	private final CandleIngestionService service = service("test-key", props);

	{
		// The benchmark (SPY) is maintained alongside the universe; keep it current so these tests
		// exercise only the ticker under test unless they say otherwise.
		when(candles.latestDateFor("SPY")).thenReturn(lastSession());
		when(candles.countByTicker("SPY")).thenReturn(300L);
	}

	private void deepAndCurrent(String ticker) {
		when(candles.latestDateFor(ticker)).thenReturn(lastSession());
		when(candles.countByTicker(ticker)).thenReturn(300L);
	}

	private CandleIngestionService service(String avKey, TechnicalAnalysisProperties p) {
		CandleIngestionService s = new CandleIngestionService(avKey, yahoo, alphaVantage, candles, universe,
				livePrices, p, notifications, new ListingResolver("VFV,DOL"), charts, events);
		s.setRequestSpacingMs(0);
		return s;
	}

	private static LocalDate lastSession() {
		return CandleIngestionService.lastCompletedSession(ZonedDateTime.now(ZoneId.of("America/New_York")));
	}

	private static YahooChartClient.Bar bar(LocalDate d) {
		BigDecimal p = new BigDecimal("100.00");
		return new YahooChartClient.Bar(d, p, p, p, p, 1000);
	}

	private static YahooChartClient.Series series(String symbol, LocalDate... dates) {
		List<YahooChartClient.Bar> bars = new ArrayList<>();
		for (LocalDate d : dates) bars.add(bar(d));
		return new YahooChartClient.Series(symbol, "USD", new BigDecimal("100"), dates.length == 0 ? null
				: dates[dates.length - 1], bars);
	}

	@Test
	void savesNewBarsFromYahooAndSkipsAlreadyStoredDates() {
		LocalDate d1 = lastSession().minusDays(3);
		LocalDate d2 = lastSession();
		when(universe.knownTickers()).thenReturn(Set.of("AAPL"));
		when(candles.latestDateFor("AAPL")).thenReturn(d1);
		when(candles.countByTicker("AAPL")).thenReturn(300L); // deep history already stored → 1-month top-up
		when(candles.datesFor("AAPL")).thenReturn(Set.of(d1));
		when(yahoo.fetch("AAPL", "1mo")).thenReturn(Optional.of(series("AAPL", d1, d2)));

		int saved = service.ingestOnce();

		assertEquals(1, saved, "the stored date is skipped; only the new session is saved");
		verify(candles, times(1)).save(any());
	}

	@Test
	void firstEverIngestRequestsAYearOfHistoryFromYahooNotAlphaVantageFull() {
		LocalDate d = lastSession();
		when(universe.knownTickers()).thenReturn(Set.of("AAPL"));
		when(candles.latestDateFor("AAPL")).thenReturn(null);
		when(yahoo.fetch("AAPL", "1y")).thenReturn(Optional.of(series("AAPL", d)));

		int saved = service.ingestOnce();

		assertEquals(1, saved);
		verify(alphaVantage, never()).get(anyString());
	}

	@Test
	void anInProgressSessionsBarIsNeverStored() {
		LocalDate done = lastSession();
		LocalDate forming = done.plusDays(1);
		when(universe.knownTickers()).thenReturn(Set.of("AAPL"));
		when(candles.latestDateFor("AAPL")).thenReturn(null);
		when(yahoo.fetch("AAPL", "1y")).thenReturn(Optional.of(series("AAPL", done, forming)));

		int saved = service.ingestOnce();

		assertEquals(1, saved, "a partial day frozen in by the dedup check would corrupt RSI/MACD");
	}

	@Test
	void tickersAlreadyCurrentAndDeepAreSkipped() {
		when(universe.knownTickers()).thenReturn(Set.of("AAPL"));
		deepAndCurrent("AAPL");

		service.ingestOnce();

		verify(yahoo, never()).fetch(anyString(), anyString());
	}

	@Test
	void aCurrentButShallowTickerIsBackfilledToAFullYear() {
		// Only ~137 bars were stored before (no SMA200 possible): current-but-shallow must refetch a year.
		when(universe.knownTickers()).thenReturn(Set.of("AAPL"));
		when(candles.latestDateFor("AAPL")).thenReturn(lastSession());
		when(candles.countByTicker("AAPL")).thenReturn(137L);
		when(yahoo.fetch("AAPL", "1y")).thenReturn(Optional.of(series("AAPL", lastSession().minusDays(3), lastSession())));

		assertEquals(2, service.ingestOnce());
		verify(yahoo).fetch("AAPL", "1y");
	}

	@Test
	void theBenchmarkIsMaintainedSoRelativeStrengthCanBeComputed() {
		when(universe.knownTickers()).thenReturn(Set.of());
		when(candles.latestDateFor("SPY")).thenReturn(null);
		when(candles.countByTicker("SPY")).thenReturn(0L);
		when(yahoo.fetch("SPY", "1y")).thenReturn(Optional.of(series("SPY", lastSession())));

		assertEquals(1, service.ingestOnce());
	}

	@Test
	void stalestTickersAreRefreshedFirst() {
		// A bounded source must never starve the tickers that need data most.
		when(universe.knownTickers()).thenReturn(new LinkedHashSet<>(List.of("AAA", "BBB", "CCC")));
		when(candles.latestDateFor("AAA")).thenReturn(lastSession().minusDays(1));
		when(candles.latestDateFor("BBB")).thenReturn(null);
		when(candles.latestDateFor("CCC")).thenReturn(lastSession().minusDays(9));
		when(candles.countByTicker("AAA")).thenReturn(300L);
		when(candles.countByTicker("CCC")).thenReturn(300L);
		when(yahoo.fetch(anyString(), anyString())).thenReturn(Optional.empty());

		service.ingestOnce();

		var order = inOrder(yahoo);
		order.verify(yahoo).fetch(eq("BBB"), anyString()); // no history at all
		order.verify(yahoo).fetch(eq("CCC"), anyString()); // 9 days stale
		order.verify(yahoo).fetch(eq("AAA"), anyString()); // 1 day stale
	}

	@Test
	void declaredTsxTickersUseOnlyTheTorontoListing() {
		// Bare DOL on Yahoo is a US ETF (~$75), not Dollarama (C$180): it must never be queried.
		when(universe.knownTickers()).thenReturn(Set.of("DOL"));
		when(candles.latestDateFor("DOL")).thenReturn(null);
		when(yahoo.fetch("DOL.TO", "1y")).thenReturn(Optional.of(series("DOL.TO", lastSession())));

		assertEquals(1, service.ingestOnce());
		verify(yahoo, never()).fetch(eq("DOL"), anyString());
	}

	@Test
	void canadianTickersTryTheTorontoListingFirst() {
		when(universe.knownTickers()).thenReturn(Set.of("XYZ"));
		when(candles.latestDateFor("XYZ")).thenReturn(null);
		when(livePrices.priceCurrency("XYZ")).thenReturn(Optional.of("CAD")); // not declared, but quoted in CAD
		when(yahoo.fetch("XYZ.TO", "1y")).thenReturn(Optional.of(series("XYZ.TO", lastSession())));

		int saved = service.ingestOnce();

		assertEquals(1, saved);
		verify(yahoo, never()).fetch(eq("XYZ"), anyString());
	}

	@Test
	void fallsBackToAlphaVantageCompactNeverFull() {
		LocalDate d = lastSession();
		when(universe.knownTickers()).thenReturn(Set.of("AAPL"));
		when(candles.latestDateFor("AAPL")).thenReturn(null);
		when(yahoo.fetch(anyString(), anyString())).thenReturn(Optional.empty());
		when(alphaVantage.get(anyString())).thenReturn(Optional.of("{\"Time Series (Daily)\":{\"" + d
				+ "\":{\"1. open\":\"1\",\"2. high\":\"1\",\"3. low\":\"1\",\"4. close\":\"1\",\"5. volume\":\"5\"}}}"));

		int saved = service.ingestOnce();

		assertEquals(1, saved);
		ArgumentCaptor<String> url = ArgumentCaptor.forClass(String.class);
		verify(alphaVantage).get(url.capture());
		assertTrue(url.getValue().contains("outputsize=compact"), "full is premium-only — it deadlocked ingestion");
		assertTrue(!url.getValue().contains("outputsize=full"));
	}

	@Test
	void noAlphaVantageCallWithoutAKey() {
		CandleIngestionService noKey = service("", props);
		when(universe.knownTickers()).thenReturn(Set.of("AAPL"));
		when(candles.latestDateFor("AAPL")).thenReturn(null);
		when(yahoo.fetch(anyString(), anyString())).thenReturn(Optional.empty());

		assertEquals(0, noKey.ingestOnce());
		verify(alphaVantage, never()).get(contains("alphavantage"));
	}

	@Test
	void barsOlderThanTheBackfillWindowAreTrimmed() {
		CandleIngestionService tight = service("test-key", new TechnicalAnalysisProperties(10));
		when(universe.knownTickers()).thenReturn(Set.of("AAPL"));
		when(candles.latestDateFor("AAPL")).thenReturn(null);
		when(yahoo.fetch("AAPL", "1y")).thenReturn(Optional.of(series("AAPL",
				LocalDate.now().minusDays(500), lastSession())));

		assertEquals(1, tight.ingestOnce());
	}

	@Test
	void oneFailingTickerDoesNotStopTheRest() {
		when(universe.knownTickers()).thenReturn(new LinkedHashSet<>(List.of("BAD", "AAPL")));
		when(candles.latestDateFor(anyString())).thenReturn(null);
		when(yahoo.fetch("AAPL", "1y")).thenReturn(Optional.of(series("AAPL", lastSession())));
		when(yahoo.fetch("BAD", "1y")).thenThrow(new IllegalStateException("boom"));

		assertEquals(1, service.ingestOnce(), "a bad symbol must not skip every other ticker's backfill");
	}

	@Test
	void malformedAlphaVantageBodyDegradesToZero() {
		when(universe.knownTickers()).thenReturn(Set.of("AAPL"));
		when(candles.latestDateFor("AAPL")).thenReturn(null);
		when(yahoo.fetch(anyString(), anyString())).thenReturn(Optional.empty());
		when(alphaVantage.get(anyString())).thenReturn(Optional.of("not json"));

		assertEquals(0, service.ingestOnce());
	}

	// ---- last completed session ----

	private static ZonedDateTime ny(int y, int m, int d, int h, int min) {
		return ZonedDateTime.of(y, m, d, h, min, 0, 0, ZoneId.of("America/New_York"));
	}

	@Test
	void lastCompletedSessionIsTodayOnlyAfterTheCloseOnAWeekday() {
		assertEquals(LocalDate.of(2026, 9, 22), CandleIngestionService.lastCompletedSession(ny(2026, 9, 23, 10, 0)),
				"Wed morning → Tue's bar is the last final one");
		assertEquals(LocalDate.of(2026, 9, 23), CandleIngestionService.lastCompletedSession(ny(2026, 9, 23, 17, 0)),
				"Wed evening → Wed's bar is final");
		assertEquals(LocalDate.of(2026, 9, 25), CandleIngestionService.lastCompletedSession(ny(2026, 9, 26, 12, 0)),
				"Saturday → Friday");
		assertEquals(LocalDate.of(2026, 9, 25), CandleIngestionService.lastCompletedSession(ny(2026, 9, 28, 9, 0)),
				"Monday before the close → the previous Friday");
	}

	// ---- splits and spin-offs ----

	private static YahooChartClient.Bar closeAt(LocalDate d, String close) {
		BigDecimal p = new BigDecimal(close);
		return new YahooChartClient.Bar(d, p, p, p, p, 1000);
	}

	@Test
	void aConsistentMismatchWithYahooIsASplit() {
		LocalDate d = LocalDate.of(2026, 9, 28);
		// RETO: stored 0.142 / 0.091 / 0.0933 before its 1-for-20 reverse split; Yahoo now shows 2.84 / 1.82 / 1.866.
		java.util.Map<LocalDate, BigDecimal> stored = java.util.Map.of(d, new BigDecimal("0.142"), d.plusDays(1),
				new BigDecimal("0.091"), d.plusDays(3), new BigDecimal("0.0933"), d.plusDays(4), new BigDecimal("1.55"));
		var adj = CandleIngestionService.readjustment(stored, List.of(closeAt(d, "2.84"), closeAt(d.plusDays(1), "1.82"),
				closeAt(d.plusDays(3), "1.866"), closeAt(d.plusDays(4), "1.55")));
		assertTrue(adj.isPresent());
		assertEquals(20.0, adj.get().factor(), 0.01);
		assertEquals(d.plusDays(3), adj.get().newestAffected(), "the post-split bar already matches");
	}

	@Test
	void aLoneDifferingBarOrMatchingHistoryIsNotASplit() {
		LocalDate d = LocalDate.of(2026, 9, 28);
		java.util.Map<LocalDate, BigDecimal> stored = java.util.Map.of(d, new BigDecimal("100"), d.plusDays(1),
				new BigDecimal("101"), d.plusDays(2), new BigDecimal("102"));
		assertTrue(CandleIngestionService.readjustment(stored, List.of(closeAt(d, "100"), closeAt(d.plusDays(1), "101.3"),
				closeAt(d.plusDays(2), "102"))).isEmpty(), "within tolerance");
		assertTrue(CandleIngestionService.readjustment(stored, List.of(closeAt(d, "100"), closeAt(d.plusDays(1), "90"),
				closeAt(d.plusDays(2), "102"))).isEmpty(), "one corrected bar is a data fix, not a split");
		assertTrue(CandleIngestionService.readjustment(stored, List.of(closeAt(d, "200"), closeAt(d.plusDays(1), "50"),
				closeAt(d.plusDays(2), "102"))).isEmpty(), "inconsistent ratios are not one corporate action");
	}

	@Test
	void aReBasedHistoryIsRebuiltAndAnnounced() {
		LocalDate last = lastSession();
		LocalDate before = last.minusDays(7);
		while (before.getDayOfWeek().getValue() > 5) {
			before = before.minusDays(1);
		}
		LocalDate older = before.minusDays(1);
		while (older.getDayOfWeek().getValue() > 5) {
			older = older.minusDays(1);
		}
		when(universe.knownTickers()).thenReturn(Set.of("RETO"));
		when(candles.latestDateFor("RETO")).thenReturn(before);
		when(candles.countByTicker("RETO")).thenReturn(300L);
		when(candles.datesFor("RETO")).thenReturn(Set.of(older, before));
		when(candles.findTop300ByTickerOrderByCandleDateDesc("RETO")).thenReturn(List.of(
				new PriceCandle("RETO", before, new BigDecimal("0.1"), new BigDecimal("0.1"), new BigDecimal("0.1"), new BigDecimal("0.1"), 1L),
				new PriceCandle("RETO", older, new BigDecimal("0.2"), new BigDecimal("0.2"), new BigDecimal("0.2"), new BigDecimal("0.2"), 1L)));
		YahooChartClient.Series rebased = new YahooChartClient.Series("RETO", "USD", null, null,
				List.of(closeAt(older, "4.0"), closeAt(before, "2.0"), closeAt(last, "1.9")));
		when(yahoo.fetch(eq("RETO"), anyString())).thenReturn(Optional.of(rebased));

		service.ingestOnce();

		verify(candles).deleteByTicker("RETO");
		verify(candles, times(3)).save(any(PriceCandle.class));
		var event = org.mockito.ArgumentCaptor.forClass(HistoryReadjusted.class);
		verify(events).publishEvent(event.capture());
		assertEquals(20.0, event.getValue().factor(), 1e-9);
		assertTrue(event.getValue().effectiveFrom().isAfter(before));
	}

	@Test
	void anOrdinaryLivePriceIsNotCheckedForASplit() {
		when(candles.findFirstByTickerOrderByCandleDateDesc("MU")).thenReturn(Optional.of(
				new PriceCandle("MU", lastSession(), new BigDecimal("100"), new BigDecimal("100"), new BigDecimal("100"), new BigDecimal("100"), 1L)));
		assertFalse(service.checkForSplit("MU", 70)); // a −30% day is a real move
		verify(yahoo, never()).fetch(anyString(), anyString());
	}
}
