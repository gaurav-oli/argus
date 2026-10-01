package com.argus.strategy;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;

/**
 * Measures a published strategy on Argus's own data, and decides whether it has earned the right to influence a
 * trade. Pure arithmetic, so the verdict can be checked against hand-built series.
 *
 * <p><b>How a strategy is scored.</b> At each rebalance date the universe is ranked by the signal; the long leg is
 * the extreme the paper says outperforms (its {@code sign}), the short leg is the opposite extreme, each
 * equal-weighted. The observation is the long-minus-short spread over the horizon. This is the same long-short
 * decile construction the papers use, which is why a wide ranking universe matters: a decile of two names is not a
 * decile.
 *
 * <p><b>Three deliberate guards against fooling ourselves:</b>
 * <ul>
 *   <li><b>Non-overlapping observations.</b> Rebalance dates are stepped by the full horizon, so a 90-day test
 *       never counts the same 90 days twice. Overlapping windows are the classic way to turn a weak signal into a
 *       t-stat of 4.</li>
 *   <li><b>A chronological hold-out.</b> The first 70% of the history fits nothing (there is nothing to fit — the
 *       rules come from the papers), but the last 30% is still kept back as an honest out-of-sample check that the
 *       edge did not simply stop existing. Post-publication decay is well documented.</li>
 *   <li><b>A published edge counts for nothing here.</b> The paper's own t-stat is recorded for context and never
 *       used in the verdict.</li>
 * </ul>
 */
public final class Backtest {

	/** A long-short spread needs enough names per leg for the leg to mean anything. */
	static final int MIN_NAMES_PER_LEG = 5;
	static final int MIN_TRAIN_OBSERVATIONS = 12;
	static final int MIN_HOLDOUT_OBSERVATIONS = 5;
	/** In-sample bar: the conventional 2.0, not the paper's. */
	static final double TRAIN_T_BAR = 2.0;
	/** Out-of-sample bar: weaker, because a hold-out has few observations by construction — but it must still agree. */
	static final double HOLDOUT_T_BAR = 1.0;
	static final double TRAIN_FRACTION = 0.7;

	private Backtest() {
	}

	public enum Verdict { PASS, FAIL_INSAMPLE, FAIL_HOLDOUT, INSUFFICIENT_DATA }

	/**
	 * One rebalance date's inputs: each ticker's signal percentile (0..1 within the universe) and its realized
	 * forward return over the horizon. Tickers missing either are simply absent.
	 */
	public record Snapshot(LocalDate date, Map<String, Double> percentile, Map<String, Double> forwardReturn) {
	}

	/** @param meanExcessPct the average long-short spread per period, in percent */
	public record Leg(int observations, double meanExcessPct, double tStat, double winRate) {
	}

	public record Result(int observations, double meanExcessPct, double tStat, double winRate, Leg train, Leg holdout,
			Verdict verdict, String note, LocalDate firstDate, LocalDate lastDate) {
	}

	/**
	 * Run the test.
	 *
	 * @param snapshots  rebalance dates, ascending, already stepped by the horizon (non-overlapping)
	 * @param sign       +1 when a high signal predicts high returns, -1 when it predicts low returns
	 * @param legQuantile fraction of the universe in each leg (0.1 = deciles)
	 */
	public static Result run(List<Snapshot> snapshots, double sign, double legQuantile) {
		List<Double> spreads = new ArrayList<>();
		List<LocalDate> dates = new ArrayList<>();
		int skippedThinUniverse = 0;
		for (Snapshot s : snapshots) {
			OptionalDouble spread = spread(s, sign, legQuantile);
			if (spread.isEmpty()) {
				skippedThinUniverse++;
				continue;
			}
			spreads.add(spread.getAsDouble());
			dates.add(s.date());
		}
		if (spreads.size() < MIN_TRAIN_OBSERVATIONS + MIN_HOLDOUT_OBSERVATIONS) {
			return new Result(spreads.size(), 0, 0, 0, null, null, Verdict.INSUFFICIENT_DATA,
					"Only " + spreads.size() + " non-overlapping period(s) could be measured"
							+ (skippedThinUniverse > 0 ? " (" + skippedThinUniverse + " skipped for too few ranked names)" : "")
							+ "; at least " + (MIN_TRAIN_OBSERVATIONS + MIN_HOLDOUT_OBSERVATIONS) + " are needed to judge it.",
					dates.isEmpty() ? null : dates.get(0), dates.isEmpty() ? null : dates.get(dates.size() - 1));
		}

		int split = (int) Math.floor(spreads.size() * TRAIN_FRACTION);
		split = Math.max(MIN_TRAIN_OBSERVATIONS, Math.min(split, spreads.size() - MIN_HOLDOUT_OBSERVATIONS));
		Leg all = leg(spreads);
		Leg train = leg(spreads.subList(0, split));
		Leg holdout = leg(spreads.subList(split, spreads.size()));

		Verdict verdict;
		String note;
		if (train.tStat() < TRAIN_T_BAR) {
			verdict = Verdict.FAIL_INSAMPLE;
			note = String.format(java.util.Locale.ROOT,
					"No edge on Argus's own data: the long-short spread averaged %+.2f%% per period in-sample (t=%.2f, needs t≥%.1f).",
					train.meanExcessPct(), train.tStat(), TRAIN_T_BAR);
		}
		else if (holdout.meanExcessPct() <= 0 || holdout.tStat() < HOLDOUT_T_BAR) {
			verdict = Verdict.FAIL_HOLDOUT;
			note = String.format(java.util.Locale.ROOT,
					"Worked in-sample (%+.2f%%, t=%.2f) but not on the held-back period (%+.2f%%, t=%.2f over %d period(s)) — "
							+ "the usual signature of an edge that has decayed or was never there.",
					train.meanExcessPct(), train.tStat(), holdout.meanExcessPct(), holdout.tStat(), holdout.observations());
		}
		else {
			verdict = Verdict.PASS;
			note = String.format(java.util.Locale.ROOT,
					"Held up out-of-sample: %+.2f%% per period in-sample (t=%.2f) and %+.2f%% on the held-back period (t=%.2f).",
					train.meanExcessPct(), train.tStat(), holdout.meanExcessPct(), holdout.tStat());
		}
		return new Result(all.observations(), all.meanExcessPct(), all.tStat(), all.winRate(), train, holdout, verdict, note,
				dates.get(0), dates.get(dates.size() - 1));
	}

	/** One date's equal-weighted long-minus-short spread in percent, or empty when either leg is too thin. */
	static OptionalDouble spread(Snapshot s, double sign, double legQuantile) {
		List<Map.Entry<String, Double>> ranked = new ArrayList<>();
		for (Map.Entry<String, Double> e : s.percentile().entrySet()) {
			if (s.forwardReturn().containsKey(e.getKey())) {
				ranked.add(e);
			}
		}
		if (ranked.size() < MIN_NAMES_PER_LEG * 2) {
			return OptionalDouble.empty();
		}
		// A median split (0.5) is a real construction in the literature — Size is sorted that way — so it is allowed;
		// only a missing or overlapping quantile falls back to deciles.
		double q = legQuantile <= 0 || legQuantile > 0.5 ? 0.1 : legQuantile;
		double hiCut = 1 - q;
		List<Double> high = new ArrayList<>();
		List<Double> low = new ArrayList<>();
		for (Map.Entry<String, Double> e : ranked) {
			double p = e.getValue();
			double fwd = s.forwardReturn().get(e.getKey());
			if (p >= hiCut) {
				high.add(fwd);
			}
			else if (p <= q) {
				low.add(fwd);
			}
		}
		if (high.size() < MIN_NAMES_PER_LEG || low.size() < MIN_NAMES_PER_LEG) {
			return OptionalDouble.empty();
		}
		double hiMean = Series.mean(high).orElseThrow();
		double loMean = Series.mean(low).orElseThrow();
		// sign +1: the high end is the long leg; sign -1: the low end is.
		double spread = sign >= 0 ? hiMean - loMean : loMean - hiMean;
		return OptionalDouble.of(spread * 100);
	}

	private static Leg leg(List<Double> spreads) {
		double mean = Series.mean(spreads).orElse(0);
		double sd = Series.stdDev(spreads).orElse(0);
		double t = sd <= 0 ? 0 : mean / (sd / Math.sqrt(spreads.size()));
		long wins = spreads.stream().filter(x -> x > 0).count();
		return new Leg(spreads.size(), mean, t, spreads.isEmpty() ? 0 : (double) wins / spreads.size());
	}

	/**
	 * Cross-sectional percentiles for one date's raw signal values: the fraction of the universe a ticker ranks
	 * above, so a signal measured in dollars and one measured in percent are directly comparable. Ties share the
	 * average rank.
	 */
	public static Map<String, Double> percentiles(Map<String, Double> values) {
		List<Map.Entry<String, Double>> sorted = new ArrayList<>(values.entrySet());
		sorted.sort(Map.Entry.comparingByValue());
		Map<String, Double> out = new LinkedHashMap<>();
		int n = sorted.size();
		if (n == 0) {
			return out;
		}
		if (n == 1) {
			out.put(sorted.get(0).getKey(), 0.5);
			return out;
		}
		int i = 0;
		while (i < n) {
			int j = i;
			while (j + 1 < n && sorted.get(j + 1).getValue().equals(sorted.get(i).getValue())) {
				j++;
			}
			double avgRank = (i + j) / 2.0;
			double p = avgRank / (n - 1);
			for (int k = i; k <= j; k++) {
				out.put(sorted.get(k).getKey(), p);
			}
			i = j + 1;
		}
		return out;
	}

	/** Rebalance dates stepped by the horizon so no two observations share a day of returns. */
	public static List<LocalDate> nonOverlappingDates(LocalDate first, LocalDate last, int horizonDays) {
		List<LocalDate> out = new ArrayList<>();
		if (first == null || last == null || horizonDays <= 0) {
			return out;
		}
		for (LocalDate d = first; !d.plusDays(horizonDays).isAfter(last); d = d.plusDays(horizonDays)) {
			out.add(d);
		}
		return out;
	}

	public static Optional<Verdict> verdictOf(String name) {
		try {
			return Optional.of(Verdict.valueOf(name));
		}
		catch (IllegalArgumentException ex) {
			return Optional.empty();
		}
	}
}
