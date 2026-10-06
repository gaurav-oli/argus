package com.argus.technical;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.argus.intelligence.KnownUniverse;
import com.argus.marketdata.ListingResolver;
import com.argus.marketdata.YahooChartClient;
import com.argus.portfolio.LivePortfolioService;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** A current price for every tracked ticker: the stream for holdings, a polled quote for the rest. */
class LivePriceServiceTest {

	private final LivePortfolioService feed = mock(LivePortfolioService.class);
	private final YahooChartClient yahoo = mock(YahooChartClient.class);
	private final KnownUniverse universe = mock(KnownUniverse.class);
	private final LivePriceService service =
			new LivePriceService(feed, yahoo, new ListingResolver("DOL"), universe);

	private static YahooChartClient.Series quote(String symbol, double price) {
		return new YahooChartClient.Series(symbol, "USD", BigDecimal.valueOf(price), LocalDate.now(), List.of());
	}

	@Test
	void pollsOnlyTickersTheStreamDoesNotPriceAndPrefersTheStream() {
		when(universe.knownTickers()).thenReturn(Set.of("AAPL", "SKHY", "DOL"));
		when(feed.latestPrice(anyString())).thenReturn(Optional.empty());
		when(feed.latestPrice("AAPL")).thenReturn(Optional.of(new BigDecimal("333.01")));
		when(yahoo.fetch("SKHY", "5d")).thenReturn(Optional.of(quote("SKHY", 187.41)));
		when(yahoo.fetch("DOL.TO", "5d")).thenReturn(Optional.of(quote("DOL.TO", 183.5)));

		service.refresh();

		verify(yahoo, never()).fetch("AAPL", "5d");
		verify(yahoo, never()).fetch("DOL", "5d"); // a TSX name is never quoted as its US namesake
		assertEquals(333.01, service.livePrice("AAPL").orElseThrow());
		assertEquals(187.41, service.livePrice("skhy").orElseThrow());
		assertEquals(183.5, service.livePrice("DOL").orElseThrow());
		assertTrue(service.livePrice("NOPE").isEmpty());
	}

	@Test
	void pollsOnlyAroundUsMarketHours() {
		assertTrue(LivePriceService.inWindow(Instant.parse("2026-10-06T15:00:00Z")));  // Tue 11:00 ET
		assertTrue(LivePriceService.inWindow(Instant.parse("2026-10-06T20:30:00Z")));  // Tue 16:30 ET, before candles land
		assertFalse(LivePriceService.inWindow(Instant.parse("2026-10-06T23:00:00Z"))); // Tue 19:00 ET
		assertFalse(LivePriceService.inWindow(Instant.parse("2026-10-10T15:00:00Z"))); // Saturday
	}
}
