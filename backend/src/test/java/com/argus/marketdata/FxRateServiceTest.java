package com.argus.marketdata;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Unit tests for the FX cache layer (Story 3.2) — client + repository mocked, no network/DB. */
class FxRateServiceTest {

	private final FxRateClient client = mock(FxRateClient.class);
	private final FxRateRepository cache = mock(FxRateRepository.class);
	private final FxRateService service = new FxRateService(client, cache);

	private final LocalDate date = LocalDate.of(2023, 1, 13);

	@Test
	void cacheMissFetchesFromClientAndWritesThrough() {
		when(cache.findById(any())).thenReturn(Optional.empty());
		when(client.usdCadOn(date)).thenReturn(Optional.of(new BigDecimal("1.3413")));
		when(client.sourceName()).thenReturn("test");

		Optional<BigDecimal> rate = service.usdCadOn(date);

		assertTrue(rate.isPresent());
		assertEquals(0, rate.get().compareTo(new BigDecimal("1.3413")));
		verify(cache).save(any(FxRate.class));
	}

	@Test
	void cacheHitDoesNotCallTheClient() {
		when(cache.findById(any())).thenReturn(
				Optional.of(new FxRate("USDCAD", date, new BigDecimal("1.3000"), "test")));

		Optional<BigDecimal> rate = service.usdCadOn(date);

		assertEquals(0, rate.get().compareTo(new BigDecimal("1.3000")));
		verify(client, never()).usdCadOn(any());
	}

	@Test
	void clientMissReturnsEmptyAndCachesNothing() {
		when(cache.findById(any())).thenReturn(Optional.empty());
		when(client.usdCadOn(date)).thenReturn(Optional.empty());

		assertTrue(service.usdCadOn(date).isEmpty());
		verify(cache, never()).save(any());
	}

	@Test
	void nullDateShortCircuits() {
		assertTrue(service.usdCadOn(null).isEmpty());
		verify(client, never()).usdCadOn(any());
	}
}
