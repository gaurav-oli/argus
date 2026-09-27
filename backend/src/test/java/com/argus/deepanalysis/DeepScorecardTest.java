package com.argus.deepanalysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.argus.deepanalysis.DeepScorecard.Bar;
import com.argus.deepanalysis.DeepScorecard.Row;
import com.argus.deepanalysis.DeepScorecard.Sample;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Agent 11 is scored against the S&P 500 only over periods that have actually elapsed. */
class DeepScorecardTest {

	private static final LocalDate D0 = LocalDate.of(2026, 6, 1);

	/** A daily series growing by {@code dailyPct} per day, for {@code days} days from D0. */
	private static List<Bar> series(double start, double dailyPct, int days) {
		List<Bar> out = new ArrayList<>();
		double p = start;
		for (int i = 0; i <= days; i++) {
			out.add(new Bar(D0.plusDays(i), p));
			p *= 1 + dailyPct / 100.0;
		}
		return out;
	}

	@Test
	void aBuyThatOutrunsTheMarketScoresAPositiveExcessAtEachElapsedHorizon() {
		Row r = DeepScorecard.evaluate(new Sample("X", DeepVerdict.WORTH_BUYING, D0, 100.0), series(100, 1.0, 40), series(100, 0.1, 40));

		assertTrue(r.matured().get(7) > 0);
		assertTrue(r.matured().get(30) > r.matured().get(7), "the lead widens with time");
		assertFalse(r.matured().containsKey(90), "90 days have not elapsed — the scorecard must not score a period that hasn't happened");
		assertTrue(r.sinceExcessPct() > 0);
	}

	@Test
	void excessIsMeasuredFromTheEntryPriceNotTheFirstBar() {
		// The stock gapped up 10% before Agent 11 recorded its price, so entry is at 110 — the gap is not credited.
		List<Bar> flat = series(100, 0.0, 40);
		Row r = DeepScorecard.evaluate(new Sample("X", DeepVerdict.WORTH_BUYING, D0, 110.0), flat, series(100, 0.0, 40));

		assertEquals(-100.0 * (1 - 100.0 / 110.0), r.matured().get(7), 1e-9);
	}

	@Test
	void aMissingEntryPriceFallsBackToTheFirstCloseOnOrAfterTheAnalysisDate() {
		Row r = DeepScorecard.evaluate(new Sample("X", DeepVerdict.WAIT, D0.plusDays(1), null), series(100, 1.0, 40), series(100, 0.0, 40));

		assertEquals(101.0, r.entryPrice(), 1e-9);
	}

	@Test
	void withoutBenchmarkOrPriceDataNothingIsScored() {
		Row noSpy = DeepScorecard.evaluate(new Sample("X", DeepVerdict.WORTH_BUYING, D0, 100.0), series(100, 1.0, 40), List.of());
		Row noStock = DeepScorecard.evaluate(new Sample("X", DeepVerdict.WORTH_BUYING, D0, null), List.of(), series(100, 1.0, 40));

		assertTrue(noSpy.matured().isEmpty());
		assertNull(noSpy.sincePct());
		assertTrue(noStock.matured().isEmpty());
		assertNull(noStock.entryPrice());
	}

	@Test
	void hitRateCountsOutperformanceForBuysAndUnderperformanceForAvoids() {
		List<Row> rows = new ArrayList<>();
		// 3 buys: 2 beat the market, 1 lags. 2 avoids: 1 lagged (right), 1 beat (wrong).
		for (double x : new double[] {5, 2, -3}) rows.add(row(DeepVerdict.WORTH_BUYING, x));
		for (double x : new double[] {-4, 6}) rows.add(row(DeepVerdict.NOT_WORTH_BUYING, x));
		rows.add(row(DeepVerdict.WAIT, 1));

		DeepScorecard.Summary s = DeepScorecard.summarize(rows);

		DeepScorecard.Cell buys = s.cells().get(DeepVerdict.WORTH_BUYING).get(30);
		assertEquals(3, buys.n());
		assertEquals(2.0 / 3.0, buys.hitRate(), 1e-9);
		assertEquals(4.0 / 3.0, buys.meanExcessPct(), 1e-9);
		assertEquals(0.5, s.cells().get(DeepVerdict.NOT_WORTH_BUYING).get(30).hitRate(), 1e-9);
		assertNull(s.cells().get(DeepVerdict.WAIT).get(30).hitRate(), "WAIT makes no directional claim, so it has no hit rate");
		assertEquals(6, s.totalVerdicts());
	}

	@Test
	void horizonsWithNoMaturedVerdictsAreAbsentNotZero() {
		DeepScorecard.Summary s = DeepScorecard.summarize(List.of(row(DeepVerdict.WORTH_BUYING, 3)));

		assertNotNull(s.cells().get(DeepVerdict.WORTH_BUYING).get(30));
		assertNull(s.cells().get(DeepVerdict.WORTH_BUYING).get(90));
		assertTrue(s.cells().get(DeepVerdict.NOT_WORTH_BUYING).isEmpty());
	}

	private static Row row(DeepVerdict v, double excess30) {
		return new Row("X", v, D0, 100.0, excess30, 0.0, excess30, java.util.Map.of(30, excess30));
	}
}
