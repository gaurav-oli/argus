package com.argus.deepanalysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
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
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

class DeepScorecardServiceTest {

	private final DeepAnalysisRepository repo = mock(DeepAnalysisRepository.class);
	private final ChartStudyService charts = mock(ChartStudyService.class);
	private final DeepScorecardSnapshotRepository snapshots = mock(DeepScorecardSnapshotRepository.class);
	private final DeepVerdictModelSnapshotRepository modelSnapshots = mock(DeepVerdictModelSnapshotRepository.class);
	private final DeepAnalysisProperties props = new DeepAnalysisProperties(true, 3, Duration.ofDays(4), 40, Duration.ofSeconds(15), true, -8.0);
	private final DeepScorecardService service = new DeepScorecardService(repo, charts, snapshots, modelSnapshots, props);

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

	private static DeepAnalysis analysisWithModel(String ticker, DeepVerdict v, int daysAgo, String model) {
		DeepAnalysis d = analysis(ticker, v, daysAgo);
		d.recordVerdictModel(model);
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


	// ---- persisted snapshot history ----

	@Test
	void snapshotNowPersistsOneRowPerMaturedCellAndSkipsEmptyOnes() {
		List<DeepAnalysis> done = new ArrayList<>();
		for (int i = 0; i < 25; i++) done.add(analysis("AAA", DeepVerdict.WORTH_BUYING, 100));
		when(repo.findByStatusIn(List.of(DeepAnalysis.Status.DONE))).thenReturn(done);
		List<PriceCandle> aaaBars = candles(100, 0.5);
		when(charts.history("AAA")).thenReturn(aaaBars);
		List<PriceCandle> spyBars = candles(100, 0.0);
		when(charts.history("SPY")).thenReturn(spyBars);

		int written = service.snapshotNow();

		// matured at 7 and 30 days (100 days old, 90-day horizon also matured) -> 3 cells for WORTH_BUYING,
		// nothing for NOT_WORTH_BUYING or WAIT (no such verdicts exist) -> those cells are empty and skipped.
		assertEquals(3, written);
		ArgumentCaptor<DeepScorecardSnapshot> captor = ArgumentCaptor.forClass(DeepScorecardSnapshot.class);
		verify(snapshots, times(3)).save(captor.capture());
		assertTrue(captor.getAllValues().stream().allMatch(row -> row.getVerdict() == DeepVerdict.WORTH_BUYING));
		assertTrue(captor.getAllValues().stream().allMatch(row -> row.getTotalVerdicts() == 25));
		assertTrue(captor.getAllValues().stream().allMatch(row -> row.getObservations() > 0), "an empty cell must never be persisted");
	}

	@Test
	void snapshotNowWritesNothingWhenThereIsNoMaturedEvidenceYet() {
		when(repo.findByStatusIn(List.of(DeepAnalysis.Status.DONE))).thenReturn(List.of());

		assertEquals(0, service.snapshotNow());
		verify(snapshots, never()).save(org.mockito.Mockito.any());
	}

	@Test
	void historyDelegatesToTheRepositoryOldestFirst() {
		DeepScorecardSnapshot row = new DeepScorecardSnapshot(DeepVerdict.WORTH_BUYING, 30,
				new DeepScorecard.Cell(25, 1.2, 0.8), 25);
		when(snapshots.findAllByOrderByComputedAtAsc()).thenReturn(List.of(row));

		assertEquals(List.of(row), service.history());
	}

	// ---- Haiku vs local model ----

	@Test
	void byModelSplitsTheTrackRecordByWhichModelAnsweredTheVerdict() {
		List<DeepAnalysis> done = new ArrayList<>();
		for (int i = 0; i < 10; i++) done.add(analysisWithModel("AAA", DeepVerdict.WORTH_BUYING, 100, "HAIKU"));
		for (int i = 0; i < 5; i++) done.add(analysisWithModel("AAA", DeepVerdict.WORTH_BUYING, 100, "LOCAL"));
		for (int i = 0; i < 3; i++) done.add(analysis("AAA", DeepVerdict.WORTH_BUYING, 100)); // pre-tracking: in neither bucket
		when(repo.findByStatusIn(List.of(DeepAnalysis.Status.DONE))).thenReturn(done);
		List<PriceCandle> aaaBars = candles(100, 0.5);
		when(charts.history("AAA")).thenReturn(aaaBars);
		List<PriceCandle> spyBars = candles(100, 0.0);
		when(charts.history("SPY")).thenReturn(spyBars);

		Map<String, DeepScorecard.Summary> byModel = service.byModel();

		assertEquals(2, byModel.size());
		assertEquals(10, byModel.get("HAIKU").totalVerdicts());
		assertEquals(5, byModel.get("LOCAL").totalVerdicts());
		assertEquals(18, service.summary().totalVerdicts(), "the overall scorecard still counts every analysis, tracked or not");
	}

	@Test
	void snapshotModelComparisonPersistsOneRowPerModelAndMaturedCell() {
		List<DeepAnalysis> done = new ArrayList<>();
		for (int i = 0; i < 10; i++) done.add(analysisWithModel("AAA", DeepVerdict.WORTH_BUYING, 100, "HAIKU"));
		for (int i = 0; i < 5; i++) done.add(analysisWithModel("AAA", DeepVerdict.WORTH_BUYING, 100, "LOCAL"));
		when(repo.findByStatusIn(List.of(DeepAnalysis.Status.DONE))).thenReturn(done);
		List<PriceCandle> aaaBars = candles(100, 0.5);
		when(charts.history("AAA")).thenReturn(aaaBars);
		List<PriceCandle> spyBars = candles(100, 0.0);
		when(charts.history("SPY")).thenReturn(spyBars);

		int written = service.snapshotModelComparisonNow();

		// 100 days old -> 7, 30 and 90-day horizons all matured -> 3 cells per model -> 6 total.
		assertEquals(6, written);
		ArgumentCaptor<DeepVerdictModelSnapshot> captor = ArgumentCaptor.forClass(DeepVerdictModelSnapshot.class);
		verify(modelSnapshots, times(6)).save(captor.capture());
		assertTrue(captor.getAllValues().stream().filter(r -> r.getModel().equals("HAIKU")).allMatch(r -> r.getObservations() == 10));
		assertTrue(captor.getAllValues().stream().filter(r -> r.getModel().equals("LOCAL")).allMatch(r -> r.getObservations() == 5));
	}

	@Test
	void snapshotModelComparisonWritesNothingWhenNoAnalysisHasATrackedModelYet() {
		List<DeepAnalysis> done = new ArrayList<>();
		for (int i = 0; i < 25; i++) done.add(analysis("AAA", DeepVerdict.WORTH_BUYING, 100)); // all pre-tracking
		when(repo.findByStatusIn(List.of(DeepAnalysis.Status.DONE))).thenReturn(done);
		List<PriceCandle> aaaBars = candles(100, 0.5);
		when(charts.history("AAA")).thenReturn(aaaBars);
		List<PriceCandle> spyBars = candles(100, 0.0);
		when(charts.history("SPY")).thenReturn(spyBars);

		assertEquals(0, service.snapshotModelComparisonNow());
		verify(modelSnapshots, never()).save(org.mockito.Mockito.any());
	}

	@Test
	void modelHistoryDelegatesToTheRepositoryOldestFirst() {
		DeepVerdictModelSnapshot row = new DeepVerdictModelSnapshot("HAIKU", DeepVerdict.WORTH_BUYING, 30, new DeepScorecard.Cell(10, 1.0, 0.7));
		when(modelSnapshots.findAllByOrderByComputedAtAsc()).thenReturn(List.of(row));

		assertEquals(List.of(row), service.modelHistory());
	}
}