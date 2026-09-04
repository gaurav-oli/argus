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
import com.argus.marketdata.AlphaVantageRest;
import java.time.LocalDate;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** Agent 10's candle ingestion: full-vs-compact window sizing, dedup, oldest-date trimming, and
 * per-ticker failure isolation (Epic: technical analysis + cause-of-move classification). Switched
 * from Finnhub (403 on the free tier's /stock/candle) to Alpha Vantage's TIME_SERIES_DAILY. */
class CandleIngestionServiceTest {

	private final AlphaVantageRest alphaVantage = mock(AlphaVantageRest.class);
	private final PriceCandleRepository candles = mock(PriceCandleRepository.class);
	private final KnownUniverse universe = mock(KnownUniverse.class);
	private final TechnicalAnalysisProperties props = new TechnicalAnalysisProperties(200, -8.0, 0.6);
	private final CandleIngestionService service =
			new CandleIngestionService("test-key", alphaVantage, candles, universe, props);

	/** Builds an Alpha Vantage TIME_SERIES_DAILY-shaped body with one entry per date, all OHLCV=100. */
	private static String dailyJson(LocalDate... dates) {
		StringBuilder series = new StringBuilder();
		for (int i = 0; i < dates.length; i++) {
			if (i > 0) series.append(",");
			series.append("\"").append(dates[i]).append("\":{\"1. open\":\"100.0000\",\"2. high\":\"100.0000\","
					+ "\"3. low\":\"100.0000\",\"4. close\":\"100.0000\",\"5. volume\":\"100\"}");
		}
		return "{\"Meta Data\":{},\"Time Series (Daily)\":{" + series + "}}";
	}

	@Test
	void firstEverIngestRequestsFullOutputSize() {
		when(universe.knownTickers()).thenReturn(Set.of("AAPL"));
		when(candles.existsByTicker("AAPL")).thenReturn(false);
		when(alphaVantage.get(anyString())).thenReturn(Optional.of(dailyJson(LocalDate.now())));

		service.ingestOnce();

		ArgumentCaptor<String> urlCaptor = ArgumentCaptor.forClass(String.class);
		verify(alphaVantage).get(urlCaptor.capture());
		assertTrue(urlCaptor.getValue().contains("outputsize=full"), "first ingest must request full history");
	}

	@Test
	void incrementalIngestRequestsCompactOutputSize() {
		when(universe.knownTickers()).thenReturn(Set.of("AAPL"));
		when(candles.existsByTicker("AAPL")).thenReturn(true);
		when(alphaVantage.get(anyString())).thenReturn(Optional.of(dailyJson(LocalDate.now())));

		service.ingestOnce();

		ArgumentCaptor<String> urlCaptor = ArgumentCaptor.forClass(String.class);
		verify(alphaVantage).get(urlCaptor.capture());
		assertTrue(urlCaptor.getValue().contains("outputsize=compact"), "incremental ingest must request compact");
	}

	@Test
	void newCandlesAreSavedAndDuplicatesAreSkipped() {
		LocalDate day1 = LocalDate.now().minusDays(2);
		LocalDate day2 = LocalDate.now().minusDays(1);
		when(universe.knownTickers()).thenReturn(Set.of("AAPL"));
		when(candles.existsByTicker("AAPL")).thenReturn(true);
		when(alphaVantage.get(anyString())).thenReturn(Optional.of(dailyJson(day1, day2)));
		when(candles.existsByTickerAndCandleDate("AAPL", day1)).thenReturn(true);
		when(candles.existsByTickerAndCandleDate("AAPL", day2)).thenReturn(false);

		int saved = service.ingestOnce();

		assertEquals(1, saved, "the already-stored date must be skipped, only the new one saved");
		verify(candles, times(1)).save(any());
	}

	@Test
	void datesOlderThanBackfillWindowAreTrimmed() {
		TechnicalAnalysisProperties tightProps = new TechnicalAnalysisProperties(10, -8.0, 0.6);
		CandleIngestionService tightService =
				new CandleIngestionService("test-key", alphaVantage, candles, universe, tightProps);
		LocalDate tooOld = LocalDate.now().minusDays(500);
		LocalDate withinWindow = LocalDate.now().minusDays(1);
		when(universe.knownTickers()).thenReturn(Set.of("AAPL"));
		when(candles.existsByTicker("AAPL")).thenReturn(false);
		when(alphaVantage.get(anyString())).thenReturn(Optional.of(dailyJson(tooOld, withinWindow)));
		when(candles.existsByTickerAndCandleDate(eq("AAPL"), any())).thenReturn(false);

		int saved = tightService.ingestOnce();

		assertEquals(1, saved, "a date far outside the configured backfill window must be trimmed");
		verify(candles, times(1)).save(any());
	}

	@Test
	void noUsableSeriesSavesNothing() {
		when(universe.knownTickers()).thenReturn(Set.of("AAPL"));
		when(candles.existsByTicker("AAPL")).thenReturn(true);
		// Alpha Vantage's shape for a bad symbol / soft rate-limit notice — a 200 body with no series.
		when(alphaVantage.get(anyString()))
				.thenReturn(Optional.of("{\"Information\":\"Invalid API call.\"}"));

		int saved = service.ingestOnce();

		assertEquals(0, saved);
		verify(candles, never()).save(any());
	}

	@Test
	void emptyResponseFromAlphaVantageSavesNothingAndDoesNotThrow() {
		when(universe.knownTickers()).thenReturn(Set.of("AAPL"));
		when(candles.existsByTicker("AAPL")).thenReturn(true);
		when(alphaVantage.get(anyString())).thenReturn(Optional.empty());

		int saved = service.ingestOnce();

		assertEquals(0, saved);
	}

	@Test
	void malformedJsonIsHandledGracefully() {
		when(universe.knownTickers()).thenReturn(Set.of("AAPL"));
		when(candles.existsByTicker("AAPL")).thenReturn(true);
		when(alphaVantage.get(anyString())).thenReturn(Optional.of("not json"));

		int saved = service.ingestOnce();

		assertEquals(0, saved, "a parse failure must degrade to zero saved, never throw out of ingestOnce");
	}

	@Test
	void oneTickerFailingDoesNotSkipTheOthersInTheSameCycle() {
		when(universe.knownTickers()).thenReturn(Set.of("BAD", "GOOD"));
		when(candles.existsByTicker(anyString())).thenReturn(true);
		when(alphaVantage.get(contains("BAD"))).thenThrow(new RuntimeException("boom"));
		when(alphaVantage.get(contains("GOOD"))).thenReturn(Optional.of(dailyJson(LocalDate.now().minusDays(1))));
		when(candles.existsByTickerAndCandleDate(eq("GOOD"), any())).thenReturn(false);

		int saved = service.ingestOnce();

		assertEquals(1, saved, "GOOD's candle must still be saved despite BAD's failure");
	}
}
