package com.argus.deepanalysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.argus.filings.FilingDigestService;
import com.argus.notification.NotificationService;
import com.argus.technical.ChartStudyService;
import com.argus.technical.PriceCandle;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class ThesisTrackerTest {

	private final DeepAnalysisRepository repo = mock(DeepAnalysisRepository.class);
	private final ChartStudyService charts = mock(ChartStudyService.class);
	private final FilingDigestService filings = mock(FilingDigestService.class);
	private final DeepAnalysisRunner runner = mock(DeepAnalysisRunner.class);
	private final NotificationService notifications = mock(NotificationService.class);
	private final DeepAnalysisProperties props = mock(DeepAnalysisProperties.class);
	private final ThesisTracker tracker = new ThesisTracker(repo, charts, filings, runner, notifications, props);

	private static DeepAnalysis buy(String ticker, double invalidation) {
		DeepAnalysis d = new DeepAnalysis(ticker, "TEST");
		d.complete(DeepVerdict.WORTH_BUYING, 30, 70, "h", "t", "b", "r", "", "", "inv", "", Duration.ofDays(10));
		d.recordEntry(100.0, invalidation);
		return d;
	}

	private void price(String ticker, double close) {
		PriceCandle c = mock(PriceCandle.class);
		when(c.getClose()).thenReturn(BigDecimal.valueOf(close));
		when(charts.recentCandles(ticker, 1)).thenReturn(List.of(c));
	}

	@Test
	void aBrokenInvalidationLevelFlagsTheVerdictQueuesReanalysisAndNotifies() {
		DeepAnalysis d = buy("AAA", 92.0);
		when(repo.latestDonePerTicker()).thenReturn(List.of(d));
		when(filings.latestPerTicker()).thenReturn(List.of());
		price("AAA", 90.0);

		int flagged = tracker.check();

		assertEquals(1, flagged);
		assertTrue(d.isAtRisk());
		assertTrue(d.getThesisReason().contains("invalidation level"));
		verify(runner).enqueue("AAA", "THESIS");
		verify(notifications).notify(any());
		verify(repo).save(d);
	}

	@Test
	void aHealthyVerdictIsOnlyMarkedChecked() {
		DeepAnalysis d = buy("BBB", 92.0);
		when(repo.latestDonePerTicker()).thenReturn(List.of(d));
		when(filings.latestPerTicker()).thenReturn(List.of());
		price("BBB", 101.0);

		assertEquals(0, tracker.check());

		assertFalse(d.isAtRisk());
		assertTrue(d.getThesisCheckedAt() != null);
		verify(runner, never()).enqueue(anyString(), anyString());
	}

	@Test
	void anAlreadyFlaggedVerdictIsNotFlaggedAgainOrRequeued() {
		DeepAnalysis d = buy("CCC", 92.0);
		d.flagAtRisk("earlier");
		when(repo.latestDonePerTicker()).thenReturn(List.of(d));
		when(filings.latestPerTicker()).thenReturn(List.of());
		price("CCC", 80.0);

		assertEquals(0, tracker.check());

		verify(runner, never()).enqueue(anyString(), anyString());
	}

	@Test
	void anExpiredVerdictAndAWaitAreLeftAlone() {
		DeepAnalysis expired = new DeepAnalysis("DDD", "TEST");
		expired.complete(DeepVerdict.WORTH_BUYING, 30, 70, "h", "t", "b", "r", "", "", "inv", "", Duration.ofDays(-1));
		expired.recordEntry(100.0, 92.0);
		DeepAnalysis wait = new DeepAnalysis("EEE", "TEST");
		wait.complete(DeepVerdict.WAIT, null, 40, "h", "t", "b", "r", "", "", "inv", "", Duration.ofDays(5));
		when(repo.latestDonePerTicker()).thenReturn(List.of(expired, wait));
		when(filings.latestPerTicker()).thenReturn(List.of());
		when(charts.recentCandles(anyString(), anyInt())).thenReturn(List.of());

		assertEquals(0, tracker.check());
	}

	@Test
	void missingPriceDataNeverFlags() {
		DeepAnalysis d = buy("FFF", 92.0);
		when(repo.latestDonePerTicker()).thenReturn(List.of(d));
		when(filings.latestPerTicker()).thenReturn(List.of());
		when(charts.recentCandles("FFF", 1)).thenReturn(List.of());

		assertEquals(0, tracker.check());
		assertFalse(d.isAtRisk());
	}
}
