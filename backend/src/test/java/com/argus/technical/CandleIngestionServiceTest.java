package com.argus.technical;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.argus.intelligence.KnownUniverse;
import com.argus.marketdata.FinnhubRest;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** Agent 10's candle ingestion: backfill-vs-incremental window sizing, dedup, and per-ticker
 * failure isolation (Epic: technical analysis + cause-of-move classification). */
class CandleIngestionServiceTest {

	private final FinnhubRest finnhub = mock(FinnhubRest.class);
	private final PriceCandleRepository candles = mock(PriceCandleRepository.class);
	private final KnownUniverse universe = mock(KnownUniverse.class);
	private final TechnicalAnalysisProperties props = new TechnicalAnalysisProperties(200, -8.0, 0.6);
	private final CandleIngestionService service =
			new CandleIngestionService("test-key", finnhub, candles, universe, props);

	private static String candleJson(String status, long... epochSeconds) {
		StringBuilder t = new StringBuilder();
		StringBuilder ohlcv = new StringBuilder();
		for (int i = 0; i < epochSeconds.length; i++) {
			if (i > 0) {
				t.append(",");
				ohlcv.append(",");
			}
			t.append(epochSeconds[i]);
			ohlcv.append("100.0");
		}
		return "{\"s\":\"" + status + "\",\"t\":[" + t + "],\"o\":[" + ohlcv + "],\"h\":[" + ohlcv
				+ "],\"l\":[" + ohlcv + "],\"c\":[" + ohlcv + "],\"v\":[" + ohlcv.toString().replace(".0", "")
				+ "]}";
	}

	@Test
	void firstEverIngestUsesTheFullBackfillWindow() {
		when(universe.knownTickers()).thenReturn(Set.of("AAPL"));
		when(candles.existsByTicker("AAPL")).thenReturn(false);
		when(finnhub.get(anyString())).thenReturn(Optional.of(candleJson("ok")));

		service.ingestOnce();

		ArgumentCaptor<String> urlCaptor = ArgumentCaptor.forClass(String.class);
		verify(finnhub).get(urlCaptor.capture());
		long fromEpoch = extractParam(urlCaptor.getValue(), "from");
		long daysBack = (LocalDate.now().atStartOfDay(ZoneOffset.UTC).toEpochSecond() - fromEpoch) / 86400;
		assertTrue(daysBack >= 199 && daysBack <= 201, "first ingest must fetch ~200 days, was " + daysBack);
	}

	@Test
	void incrementalIngestUsesASmallOverlapWindow() {
		when(universe.knownTickers()).thenReturn(Set.of("AAPL"));
		when(candles.existsByTicker("AAPL")).thenReturn(true);
		when(finnhub.get(anyString())).thenReturn(Optional.of(candleJson("ok")));

		service.ingestOnce();

		ArgumentCaptor<String> urlCaptor = ArgumentCaptor.forClass(String.class);
		verify(finnhub).get(urlCaptor.capture());
		long fromEpoch = extractParam(urlCaptor.getValue(), "from");
		long daysBack = (LocalDate.now().atStartOfDay(ZoneOffset.UTC).toEpochSecond() - fromEpoch) / 86400;
		assertTrue(daysBack <= 6, "incremental ingest must be a small window, was " + daysBack + " days");
	}

	@Test
	void newCandlesAreSavedAndDuplicatesAreSkipped() {
		long day1 = Instant.parse("2026-01-01T00:00:00Z").getEpochSecond();
		long day2 = Instant.parse("2026-01-02T00:00:00Z").getEpochSecond();
		when(universe.knownTickers()).thenReturn(Set.of("AAPL"));
		when(candles.existsByTicker("AAPL")).thenReturn(true);
		when(finnhub.get(anyString())).thenReturn(Optional.of(candleJson("ok", day1, day2)));
		when(candles.existsByTickerAndCandleDate("AAPL", LocalDate.of(2026, 1, 1))).thenReturn(true);
		when(candles.existsByTickerAndCandleDate("AAPL", LocalDate.of(2026, 1, 2))).thenReturn(false);

		int saved = service.ingestOnce();

		assertEquals(1, saved, "the already-stored date must be skipped, only the new one saved");
		verify(candles, times(1)).save(any());
	}

	@Test
	void noDataStatusSavesNothing() {
		when(universe.knownTickers()).thenReturn(Set.of("AAPL"));
		when(candles.existsByTicker("AAPL")).thenReturn(true);
		when(finnhub.get(anyString())).thenReturn(Optional.of(candleJson("no_data")));

		int saved = service.ingestOnce();

		assertEquals(0, saved);
		verify(candles, never()).save(any());
	}

	@Test
	void emptyResponseFromFinnhubSavesNothingAndDoesNotThrow() {
		when(universe.knownTickers()).thenReturn(Set.of("AAPL"));
		when(candles.existsByTicker("AAPL")).thenReturn(true);
		when(finnhub.get(anyString())).thenReturn(Optional.empty());

		int saved = service.ingestOnce();

		assertEquals(0, saved);
	}

	@Test
	void malformedJsonIsHandledGracefully() {
		when(universe.knownTickers()).thenReturn(Set.of("AAPL"));
		when(candles.existsByTicker("AAPL")).thenReturn(true);
		when(finnhub.get(anyString())).thenReturn(Optional.of("not json"));

		int saved = service.ingestOnce();

		assertEquals(0, saved, "a parse failure must degrade to zero saved, never throw out of ingestOnce");
	}

	@Test
	void oneTickerFailingDoesNotSkipTheOthersInTheSameCycle() {
		when(universe.knownTickers()).thenReturn(Set.of("BAD", "GOOD"));
		when(candles.existsByTicker(anyString())).thenReturn(true);
		when(finnhub.get(contains("BAD"))).thenThrow(new RuntimeException("boom"));
		when(finnhub.get(contains("GOOD"))).thenReturn(Optional.of(candleJson("ok",
				Instant.parse("2026-01-01T00:00:00Z").getEpochSecond())));
		when(candles.existsByTickerAndCandleDate(eq("GOOD"), any())).thenReturn(false);

		int saved = service.ingestOnce();

		assertEquals(1, saved, "GOOD's candle must still be saved despite BAD's failure");
	}

	private static long extractParam(String url, String name) {
		String marker = name + "=";
		int start = url.indexOf(marker) + marker.length();
		int end = url.indexOf('&', start);
		return Long.parseLong(url.substring(start, end < 0 ? url.length() : end));
	}
}
