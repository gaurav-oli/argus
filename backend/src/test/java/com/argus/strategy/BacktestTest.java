package com.argus.strategy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The gate a published strategy has to pass. A paper's reputation buys nothing here — only measured, held-out edge does. */
class BacktestTest {

	private static final LocalDate START = LocalDate.of(2015, 1, 2);

	/**
	 * {@code edgePct} is the per-period spread the top decile earns over the bottom. {@code decayAfter} switches the
	 * edge off from that observation on, which is what post-publication decay looks like in the data.
	 */
	private static List<Backtest.Snapshot> snapshots(int periods, double edgePct, int decayAfter, int names) {
		List<Backtest.Snapshot> out = new ArrayList<>();
		for (int p = 0; p < periods; p++) {
			Map<String, Double> pct = new LinkedHashMap<>();
			Map<String, Double> fwd = new LinkedHashMap<>();
			double edge = p < decayAfter ? edgePct : 0.0;
			for (int i = 0; i < names; i++) {
				String t = "T" + i;
				double percentile = names == 1 ? 0.5 : (double) i / (names - 1);
				pct.put(t, percentile);
				// a mild deterministic wobble so the spread has variance to form a t-stat from
				double wobble = ((p * 7 + i * 13) % 11 - 5) * 0.002;
				fwd.put(t, (percentile >= 0.9 ? edge / 100.0 : 0.0) + wobble);
			}
			out.add(new Backtest.Snapshot(START.plusMonths(p), pct, fwd));
		}
		return out;
	}

	@Test
	void aRealPersistentEdgePasses() {
		Backtest.Result r = Backtest.run(snapshots(40, 2.0, 999, 50), 1.0, 0.1);

		assertEquals(Backtest.Verdict.PASS, r.verdict(), r.note());
		assertTrue(r.meanExcessPct() > 0);
		assertTrue(r.train().tStat() >= Backtest.TRAIN_T_BAR);
		assertTrue(r.holdout().tStat() >= Backtest.HOLDOUT_T_BAR);
		assertTrue(r.note().contains("Held up out-of-sample"), r.note());
	}

	@Test
	void anEdgeThatDecaysAfterPublicationFailsTheHoldOutNotTheInSample() {
		// strong for the first 70% of the history, gone for the last 30% — exactly what the hold-out exists to catch
		Backtest.Result r = Backtest.run(snapshots(40, 4.0, 28, 50), 1.0, 0.1);

		assertEquals(Backtest.Verdict.FAIL_HOLDOUT, r.verdict(), r.note());
		assertTrue(r.train().tStat() >= Backtest.TRAIN_T_BAR, "it did work in-sample");
		assertTrue(r.note().contains("not on the held-back period"), r.note());
	}

	@Test
	void noEdgeAtAllFailsInSample() {
		Backtest.Result r = Backtest.run(snapshots(40, 0.0, 999, 50), 1.0, 0.1);

		assertEquals(Backtest.Verdict.FAIL_INSAMPLE, r.verdict(), r.note());
		assertTrue(r.note().contains("No edge on Argus's own data"), r.note());
	}

	@Test
	void tooLittleHistoryIsReportedAsSuchRatherThanJudged() {
		Backtest.Result r = Backtest.run(snapshots(10, 5.0, 999, 50), 1.0, 0.1);

		assertEquals(Backtest.Verdict.INSUFFICIENT_DATA, r.verdict());
		assertTrue(r.note().contains("at least"), r.note());
	}

	@Test
	void aUniverseTooThinForTwoLegsIsNotScored() {
		// 8 names cannot give two legs of 5
		Backtest.Result r = Backtest.run(snapshots(40, 5.0, 999, 8), 1.0, 0.1);

		assertEquals(Backtest.Verdict.INSUFFICIENT_DATA, r.verdict());
		assertTrue(r.note().contains("too few ranked names"), r.note());
	}

	@Test
	void theSignDecidesWhichEndIsTheLongLeg() {
		List<Backtest.Snapshot> s = snapshots(40, 2.0, 999, 50);

		assertEquals(Backtest.Verdict.PASS, Backtest.run(s, 1.0, 0.1).verdict(), "high end outperforms, sign +1 is right");
		Backtest.Result flipped = Backtest.run(s, -1.0, 0.1);
		assertTrue(flipped.meanExcessPct() < 0, "reading it upside down must lose, not win");
		assertEquals(Backtest.Verdict.FAIL_INSAMPLE, flipped.verdict());
	}

	@Test
	void spreadIsLongMinusShortAndRespectsTheLegQuantile() {
		Map<String, Double> pct = new LinkedHashMap<>();
		Map<String, Double> fwd = new LinkedHashMap<>();
		for (int i = 0; i < 20; i++) {
			pct.put("T" + i, i / 19.0);
			fwd.put("T" + i, i / 19.0 >= 0.75 ? 0.10 : 0.0); // the top quarter gains 10%
		}
		Backtest.Snapshot snap = new Backtest.Snapshot(START, pct, fwd);

		// quartile legs: the whole top leg gained 10%, the bottom gained nothing
		assertEquals(10.0, Backtest.spread(snap, 1.0, 0.25).orElseThrow(), 1e-9);
	}

	@Test
	void percentilesSpanZeroToOneAndTiesShareTheirRank() {
		Map<String, Double> p = Backtest.percentiles(new LinkedHashMap<>(Map.of("a", 1.0, "b", 2.0, "c", 3.0)));

		assertEquals(0.0, p.get("a"), 1e-9);
		assertEquals(0.5, p.get("b"), 1e-9);
		assertEquals(1.0, p.get("c"), 1e-9);

		Map<String, Double> tied = Backtest.percentiles(new LinkedHashMap<>(Map.of("a", 5.0, "b", 5.0, "c", 9.0)));
		assertEquals(tied.get("a"), tied.get("b"), 1e-9, "equal values must not be ranked arbitrarily");
		assertEquals(1.0, tied.get("c"), 1e-9);

		assertEquals(0.5, Backtest.percentiles(new LinkedHashMap<>(Map.of("only", 1.0))).get("only"), 1e-9);
		assertTrue(Backtest.percentiles(new LinkedHashMap<>()).isEmpty());
	}

	@Test
	void rebalanceDatesNeverOverlapSoATStatCannotBeInflated() {
		List<LocalDate> dates = Backtest.nonOverlappingDates(START, START.plusDays(100), 30);

		assertEquals(List.of(START, START.plusDays(30), START.plusDays(60)), dates);
		for (int i = 1; i < dates.size(); i++) {
			assertFalse(dates.get(i).isBefore(dates.get(i - 1).plusDays(30)), "windows must not share days of return");
		}
		assertTrue(Backtest.nonOverlappingDates(START, START.plusDays(10), 30).isEmpty());
		assertTrue(Backtest.nonOverlappingDates(null, START, 30).isEmpty());
	}

	@Test
	void tickersMissingAForwardReturnAreDroppedNotTreatedAsFlat() {
		Map<String, Double> pct = new LinkedHashMap<>();
		Map<String, Double> fwd = new LinkedHashMap<>();
		for (int i = 0; i < 20; i++) {
			pct.put("T" + i, i / 19.0);
			if (i % 2 == 0) {
				fwd.put("T" + i, 0.01);
			}
		}
		// only 10 of the 20 have a forward return, so each leg is formed from those 10
		assertTrue(Backtest.spread(new Backtest.Snapshot(START, pct, fwd), 1.0, 0.5).isPresent());
	}
}
