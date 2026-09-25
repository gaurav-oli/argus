package com.argus.fundamentals;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.argus.intelligence.KnownUniverse;
import com.argus.marketdata.FinnhubRest;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class FundamentalsServiceTest {

	private final FinnhubRest finnhub = mock(FinnhubRest.class);
	private final FundamentalsSnapshotRepository repo = mock(FundamentalsSnapshotRepository.class);
	private final KnownUniverse universe = mock(KnownUniverse.class);
	private final FundamentalsService service = new FundamentalsService(finnhub, "key", repo, universe);

	/** The stored row, rebuilt from what the service passed to the upsert. */
	private FundamentalsSnapshot stored() {
		ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
		ArgumentCaptor<Boolean> applicable = ArgumentCaptor.forClass(Boolean.class);
		ArgumentCaptor<Double> score = ArgumentCaptor.forClass(Double.class);
		ArgumentCaptor<String> bias = ArgumentCaptor.forClass(String.class);
		ArgumentCaptor<Instant> at = ArgumentCaptor.forClass(Instant.class);
		verify(repo).upsert(anyString(), payload.capture(), applicable.capture(), score.capture(), bias.capture(), at.capture());
		return new FundamentalsSnapshot("ACME", payload.getValue(), applicable.getValue(), score.getValue(), bias.getValue(), at.getValue());
	}

	private void stubFinnhub(String profile, String metric) {
		when(finnhub.get(anyString())).thenAnswer(inv -> {
			String url = inv.getArgument(0);
			if (url.contains("stock/profile2")) return Optional.ofNullable(profile);
			if (url.contains("stock/metric?symbol=ACME")) return Optional.ofNullable(metric);
			if (url.contains("stock/metric?symbol=P")) return Optional.of("{\"metric\":{\"peTTM\":40.0}}");
			if (url.contains("stock/peers")) return Optional.of("[\"ACME\",\"P1\",\"P2\",\"P3\"]");
			if (url.contains("stock/earnings")) return Optional.of(
					"[{\"period\":\"2026-06-30\",\"actual\":1.2,\"estimate\":1.0,\"surprisePercent\":20.0}]");
			if (url.contains("stock/recommendation")) return Optional.of(
					"[{\"period\":\"2026-09-01\",\"strongBuy\":10,\"buy\":10,\"hold\":2,\"sell\":0,\"strongSell\":0}]");
			if (url.contains("stock/financials-reported")) return Optional.of("{\"data\":[]}");
			return Optional.empty();
		});
	}

	@Test
	void refreshFetchesAnalysesStoresAndTheSnapshotRoundTrips() {
		stubFinnhub("{\"name\":\"Acme Corp\",\"marketCapitalization\":1000,\"finnhubIndustry\":\"Tech\"}",
				"{\"metric\":{\"peTTM\":20.0,\"revenueGrowthQuarterlyYoy\":25.0}}");

		Fundamentals f = service.refresh("acme").orElseThrow();

		assertTrue(f.applicable());
		assertEquals("ACME", f.ticker());
		assertEquals(3, f.peers().peers().size(), "the ticker itself is excluded from its own peers");
		assertEquals(40.0, f.peers().medianPe(), 1e-9);

		FundamentalsSnapshot snapshot = stored();
		when(repo.findById("ACME")).thenReturn(Optional.of(snapshot));
		Fundamentals back = service.latest("ACME").orElseThrow();
		assertEquals(f.score(), back.score(), 1e-9, "the stored payload must deserialize to the same analysis");
		assertEquals(f.notes(), back.notes());
	}

	@Test
	void aFinnhubOutageStoresNothingSoGoodDataIsNeverOverwritten() {
		when(finnhub.get(anyString())).thenReturn(Optional.empty());

		assertTrue(service.refresh("ACME").isEmpty());
		verify(repo, never()).upsert(anyString(), anyString(), org.mockito.ArgumentMatchers.anyBoolean(), org.mockito.ArgumentMatchers.anyDouble(), anyString(), any());
	}

	@Test
	void anEtfIsStoredAsNotApplicableSoItIsNotRefetchedEveryNight() {
		stubFinnhub("{}", "{\"metric\":{}}");

		Fundamentals f = service.refresh("ACME").orElseThrow();

		assertFalse(f.applicable());
		assertFalse(stored().isApplicable());
	}

	@Test
	void noApiKeyMeansNoCallsAtAll() {
		FundamentalsService noKey = new FundamentalsService(finnhub, "", repo, universe);

		assertTrue(noKey.refresh("ACME").isEmpty());
		verify(finnhub, never()).get(anyString());
	}

	@Test
	void aFreshStoredSnapshotIsServedWithoutCallingFinnhub() {
		stubFinnhub("{\"name\":\"Acme\",\"marketCapitalization\":1}", "{\"metric\":{\"peTTM\":10.0}}");
		AtomicReference<FundamentalsSnapshot> store = new AtomicReference<>();
		service.refresh("ACME");
		store.set(stored());
		when(repo.findById("ACME")).thenReturn(Optional.of(store.get()));
		org.mockito.Mockito.clearInvocations(finnhub);

		Optional<Fundamentals> got = service.getOrRefresh("ACME", Duration.ofHours(24));

		assertTrue(got.isPresent());
		verify(finnhub, never()).get(anyString());
		assertTrue(store.get().getFetchedAt().isAfter(Instant.now().minusSeconds(60)));
	}

	@Test
	void aStaleSnapshotIsRefreshedButFallsBackToItIfFinnhubIsDown() {
		stubFinnhub("{\"name\":\"Acme\",\"marketCapitalization\":1}", "{\"metric\":{\"peTTM\":10.0}}");
		service.refresh("ACME");
		FundamentalsSnapshot snapshot = stored();
		when(repo.findById("ACME")).thenReturn(Optional.of(snapshot));
		when(finnhub.get(anyString())).thenReturn(Optional.empty()); // outage now

		Optional<Fundamentals> got = service.getOrRefresh("ACME", Duration.ZERO);

		assertTrue(got.isPresent(), "an outage must fall back to the last stored snapshot, not lose it");
	}

	@Test
	void aSecondRefreshRightAfterTheFirstReusesItInsteadOfRepeatingTheFinnhubCalls() {
		// The boot fill and Agent 11's evidence step can ask for the same ticker at once; the second must not
		// spend another ~10 Finnhub calls (nor race the first to write the row).
		stubFinnhub("{\"name\":\"Acme\",\"marketCapitalization\":1}", "{\"metric\":{\"peTTM\":10.0}}");
		service.refresh("ACME");
		FundamentalsSnapshot snapshot = stored();
		when(repo.findById("ACME")).thenReturn(Optional.of(snapshot));
		org.mockito.Mockito.clearInvocations(finnhub);

		Optional<Fundamentals> second = service.refresh("ACME");

		assertTrue(second.isPresent());
		verify(finnhub, never()).get(anyString());
	}
}
