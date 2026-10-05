package com.argus.portfolio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.argus.marketdata.ListingResolver;
import com.argus.marketdata.PriceFeed;
import java.util.Collection;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

/** DOL mispricing bug: a declared-TSX ticker must never reach Finnhub, since the bare symbol can
 * silently stream a DIFFERENT US instrument (bare DOL is a US ETF, not Dollarama) — a wrong-but-
 * present price that then hides from the TSX feed's "unpriced" check forever. */
class PriceFeedStarterTest {

	@SuppressWarnings("unchecked")
	private final ObjectProvider<PriceFeed> priceFeedProvider = mock(ObjectProvider.class);
	private final PriceFeed feed = mock(PriceFeed.class);
	private final PositionRepository positions = mock(PositionRepository.class);
	private final LivePortfolioService live = mock(LivePortfolioService.class);
	private final ListingResolver listing = new ListingResolver("DOL,VFV");
	private final PriceFeedStarter starter = new PriceFeedStarter(priceFeedProvider, positions, live, listing);

	@Test
	void declaredTsxTickersAreNeverHandedToTheFeed() {
		when(priceFeedProvider.getIfAvailable()).thenReturn(feed);
		when(positions.allTickersAcrossAllUsers()).thenReturn(List.of("AAPL", "DOL", "VFV", "MSFT"));

		starter.startFeed();

		ArgumentCaptor<java.util.function.Supplier<Collection<String>>> symbolsCaptor =
				ArgumentCaptor.forClass(java.util.function.Supplier.class);
		verify(feed).start(symbolsCaptor.capture(), any(), any());
		assertEquals(List.of("AAPL", "MSFT"), symbolsCaptor.getValue().get(),
				"DOL/VFV are declared TSX — Finnhub must never subscribe to the bare (wrong-instrument) symbol");
	}

	@Test
	void resubscribeReadsTheSameFilteredSupplier() {
		when(priceFeedProvider.getIfAvailable()).thenReturn(feed);
		when(positions.allTickersAcrossAllUsers()).thenReturn(List.of("DOL", "AAPL"));

		starter.startFeed();

		ArgumentCaptor<java.util.function.Supplier<Collection<String>>> symbolsCaptor =
				ArgumentCaptor.forClass(java.util.function.Supplier.class);
		verify(feed).start(symbolsCaptor.capture(), any(), any());
		// The supplier is re-read lazily (it's the same lambda PriceFeed.resubscribe() calls), so a
		// later holdings change is reflected without re-wiring anything.
		when(positions.allTickersAcrossAllUsers()).thenReturn(List.of("DOL", "AAPL", "GOOGL"));
		assertEquals(List.of("AAPL", "GOOGL"), symbolsCaptor.getValue().get());
	}
}
