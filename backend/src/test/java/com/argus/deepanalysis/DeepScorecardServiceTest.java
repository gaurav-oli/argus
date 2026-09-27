package com.argus.deepanalysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.argus.technical.ChartStudyService;
import com.argus.technical.PriceCandle;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class DeepScorecardServiceTest {

	private final DeepAnalysisRepository repo = mock(DeepAnalysisRepository.class);
	private final ChartStudyService charts = mock(ChartStudyService.class);
	private final DeepScorecardService service = new DeepScorecardService(repo, charts);

	private static final LocalDate START = LocalDate.now().minusDays(120);

	private static List<PriceCandle> candles(double start, double dailyPct) {
		List<PriceCandle> out = new ArrayList<>();
		double p = start;
		for (int i = 0; i <= 120; i++) {
			PriceCandle c = mock(PriceCandle.class);
			when(c.getCandleDate()).thenReturn(START.plusDays(i));
			when(c.getClose()).thenReturn(BigDecimal.valueOf(p));
			out.add(c);
			p *= 1 + dailyPct / 100.0;
		}
		return out;
	}

	private static DeepAnalysis analysis(String ticker, DeepVerdict v, int daysAgo) {
		DeepAnalysis d = new DeepAnalysis(ticker, "TEST");
		d.complete(v, v == DeepVerdict.WORTH_BUYING ? 30 : null, 70, "h", "t", "b", "r", "", "", "i", "", Duration.ofDays(30));
		ReflectionTestUtils.setField(d, "finishedAt", LocalDate.now().minusDays(daysAgo).atStartOfDay().toInstant(ZoneOffset.UTC));
		return d;
	}

	@Test
	void aRecordOfBuysThatBeatTheMarketBecomesAHighHitRateTrackRecord() {
		List<DeepAnalysis> done = new ArrayList<>();
		for (int i = 0; i < 25; i++) done.add(analysis("AAA", DeepVerdict.WORTH_BUYING, 100));
		when(repo.findByStatusIn(List.of(DeepAnalysis.Status.DONE))).thenReturn(done);
		List<PriceCandle> aaaBars = candles(100, 0.5);
		when(charts.history("AAA")).thenReturn(aaaBars);
		List<PriceCandle> spyBars = candles(100, 0.0);
		when(charts.history("SPY")).thenReturn(spyBars);

		TrackRecord tr = service.trackRecord(DeepVerdict.WORTH_BUYING).orElseThrow();

		assertEquals(25, tr.n());
		assertEquals(1.0, tr.hitRate(), 1e-9);
		assertEquals(30, tr.horizonDays(), "the 30-day horizon is preferred when it has enough matured verdicts");
		assertTrue(tr.meanExcessPct() > 0);
	}

	@Test
	void tooFewMaturedVerdictsIsNotATrackRecord() {
		List<DeepAnalysis> done = new ArrayList<>();
		for (int i = 0; i < 19; i++) done.add(analysis("AAA", DeepVerdict.WORTH_BUYING, 100));
		when(repo.findByStatusIn(List.of(DeepAnalysis.Status.DONE))).thenReturn(done);
		List<PriceCandle> aaaBars = candles(100, -0.5);
		when(charts.history("AAA")).thenReturn(aaaBars);
		List<PriceCandle> spyBars = candles(100, 0.0);
		when(charts.history("SPY")).thenReturn(spyBars);

		assertTrue(service.trackRecord(DeepVerdict.WORTH_BUYING).isEmpty(), "19 < 20: noise, not a record");
	}

	@Test
	void recentVerdictsHaveNotMaturedAndDoNotCount() {
		List<DeepAnalysis> done = new ArrayList<>();
		for (int i = 0; i < 30; i++) done.add(analysis("AAA", DeepVerdict.WORTH_BUYING, 3));
		when(repo.findByStatusIn(List.of(DeepAnalysis.Status.DONE))).thenReturn(done);
		List<PriceCandle> aaaBars = candles(100, 0.5);
		when(charts.history("AAA")).thenReturn(aaaBars);
		List<PriceCandle> spyBars = candles(100, 0.0);
		when(charts.history("SPY")).thenReturn(spyBars);

		assertTrue(service.trackRecord(DeepVerdict.WORTH_BUYING).isEmpty());
		assertEquals(30, service.summary().totalVerdicts());
	}

	@Test
	void fallsBackToTheSevenDayHorizonWhenTheThirtyDayOneIsThin() {
		List<DeepAnalysis> done = new ArrayList<>();
		for (int i = 0; i < 12; i++) done.add(analysis("AAA", DeepVerdict.WORTH_BUYING, 100));  // matured at 7 and 30
		for (int i = 0; i < 13; i++) done.add(analysis("AAA", DeepVerdict.WORTH_BUYING, 15));   // matured at 7 only
		when(repo.findByStatusIn(List.of(DeepAnalysis.Status.DONE))).thenReturn(done);
		List<PriceCandle> aaaBars = candles(100, 0.5);
		when(charts.history("AAA")).thenReturn(aaaBars);
		List<PriceCandle> spyBars = candles(100, 0.0);
		when(charts.history("SPY")).thenReturn(spyBars);

		TrackRecord tr = service.trackRecord(DeepVerdict.WORTH_BUYING).orElseThrow();

		assertEquals(7, tr.horizonDays());
		assertEquals(25, tr.n());
	}
}
