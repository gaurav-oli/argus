package com.argus.deepanalysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.argus.common.BadRequestException;
import com.argus.intelligence.KnownUniverse;
import com.argus.intelligence.PortfolioKnownUniverse;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class DeepAnalysisRunnerTest {

	private final DeepAnalysisRepository repo = mock(DeepAnalysisRepository.class);
	private final DeepAnalystService analyst = mock(DeepAnalystService.class);
	private final KnownUniverse universe = mock(KnownUniverse.class);
	private final PortfolioKnownUniverse held = mock(PortfolioKnownUniverse.class);
	private DeepAnalysisRunner runner(int maxPerRun) {
		return new DeepAnalysisRunner(repo, analyst, new DeepAnalysisProperties(true, 3, Duration.ofDays(4), maxPerRun, Duration.ZERO, true, -8.0),
				universe, held);
	}

	@Test
	void nightlyOrderIsHeldTickersFirstThenTheStalestAndSkipsFreshOnes() {
		when(universe.knownTickers()).thenReturn(new LinkedHashSet<>(List.of("AAA", "BBB", "CCC", "DDD", "EEE")));
		when(held.knownTickers()).thenReturn(Set.of("CCC"));
		Instant now = Instant.now();
		when(repo.lastFinished("AAA")).thenReturn(now.minus(Duration.ofDays(10)));
		when(repo.lastFinished("BBB")).thenReturn(null); // never analysed
		when(repo.lastFinished("CCC")).thenReturn(now.minus(Duration.ofDays(4)));
		when(repo.lastFinished("DDD")).thenReturn(now.minus(Duration.ofDays(1))); // fresh: skipped
		when(repo.lastFinished("EEE")).thenReturn(now.minus(Duration.ofDays(20)));

		List<String> order = runner(40).nightlyOrder();

		assertEquals(List.of("CCC", "BBB", "EEE", "AAA"), order, "held first; then never-analysed, then stalest; fresh DDD skipped");
	}

	@Test
	void nightlyOrderRespectsThePerRunCap() {
		when(universe.knownTickers()).thenReturn(new LinkedHashSet<>(List.of("AAA", "BBB", "CCC")));
		when(held.knownTickers()).thenReturn(Set.of());
		when(repo.lastFinished(anyString())).thenReturn(null);

		assertEquals(2, runner(2).nightlyOrder().size());
	}

	@Test
	void enqueueQueuesANewRunAndSubmitsItToTheBackgroundWorker() {
		when(repo.findFirstByTickerAndStatusInOrderByCreatedAtDesc(anyString(), any())).thenReturn(Optional.empty());
		when(repo.save(any(DeepAnalysis.class))).thenAnswer(inv -> {
			DeepAnalysis d = inv.getArgument(0);
			org.springframework.test.util.ReflectionTestUtils.setField(d, "id", 7L);
			return d;
		});

		DeepAnalysis run = runner(40).enqueue(" nvda ", "MANUAL");

		assertEquals("NVDA", run.getTicker());
		verify(analyst, timeout(2000)).analyze(7L);
	}

	@Test
	void aTickerAlreadyQueuedOrRunningIsNeverQueuedTwice() {
		DeepAnalysis existing = new DeepAnalysis("NVDA", "NIGHTLY");
		when(repo.findFirstByTickerAndStatusInOrderByCreatedAtDesc(anyString(), any())).thenReturn(Optional.of(existing));

		DeepAnalysis got = runner(40).enqueue("NVDA", "MANUAL");

		assertSame(existing, got);
		verify(repo, never()).save(any(DeepAnalysis.class));
		verify(analyst, never()).analyze(anyLong());
	}

	@Test
	void malformedTickersAreRejectedBeforeTouchingTheQueue() {
		DeepAnalysisRunner r = runner(40);

		assertThrows(BadRequestException.class, () -> r.enqueue("", "MANUAL"));
		assertThrows(BadRequestException.class, () -> r.enqueue("not a ticker!", "MANUAL"));
		verify(repo, never()).save(any(DeepAnalysis.class));
	}
}
