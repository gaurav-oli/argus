package com.argus.grading;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The report card's arithmetic (strategy rebuild, Phase 1) — pure, so every rule is unit-testable.
 *
 * <p>An agent is graded on what its calls did <em>after</em> it made them: each graded prediction carries the stock's
 * return minus SPY's over the agent's own timeframe, signed by the agent's direction ("edge"). Three things keep that
 * honest:
 * <ul>
 *   <li><b>Independent samples.</b> Daily calls on the same stock overlap, and stocks called on the same day in the
 *       same sector move together. Predictions are pooled into clusters (one non-overlapping period × sector) and
 *       the clusters, not the raw calls, are what get counted and averaged.</li>
 *   <li><b>No grade until there's enough.</b> Below {@link #MIN_CLUSTERS} clusters an agent is UNGRADED and keeps the
 *       weight it already has — "no grade yet" is not "bad".</li>
 *   <li><b>Gradual moves.</b> A target weight is derived from the grade, and the live weight steps toward it by at
 *       most {@link #STEP} a night, so one wild week can't swing everything.</li>
 * </ul>
 */
public final class ReportCardMath {

	public static final int MIN_CLUSTERS = 30;
	/** Shrinkage strength: an edge measured on n clusters counts n/(n+K) of itself. */
	static final double SHRINK_K = 30;
	/** A situation needs this many clusters before it gets its own weight (else it uses the overall one). */
	public static final int SITUATION_MIN_CLUSTERS = 10;
	public static final double STEP = 0.10;
	static final double GOOD_T = 1.0;
	static final double BAD_T = -1.0;
	static final double FLIP_T = -2.0;
	/** Each situation must have at least this many clusters, all reliably wrong, before an agent is flipped. */
	static final int FLIP_SITUATION_MIN = 15;
	public static final double MAX_MULTIPLIER = 2.0;
	static final double COIN_MULTIPLIER = 0.6;
	static final double FLIP_MULTIPLIER = -0.5;
	/** Trust given up by an agent whose strong calls aren't more often right than its weak ones. */
	static final double OVERCONFIDENCE_PENALTY = 0.85;

	public static final String RISK_OFF = "RISK_OFF";
	public static final String CALM = "CALM";
	public static final String EARNINGS = "EARNINGS";

	public enum State { UNGRADED, GOOD, COIN_FLIP, MUTED, FLIPPED }

	/** One graded prediction. {@code sign} is +1 bullish / −1 bearish; {@code excess} is stock minus SPY, in points. */
	public record Obs(LocalDate date, String sector, double strength, int sign, double excess, String regime,
			boolean earningsWeek) {

		double edge() {
			return sign * excess;
		}
	}

	public record Grade(int calls, int clusters, double edge, double tStat, double hitRate) {

		static final Grade EMPTY = new Grade(0, 0, 0, 0, 0);

		double shrunkEdge() {
			return clusters == 0 ? 0 : edge * clusters / (clusters + SHRINK_K);
		}
	}

	public record Situation(Grade grade, double target, double multiplier) {
	}

	public record Card(String agent, int horizonDays, Grade overall, State state, double target, double multiplier,
			Double strongEdge, Double weakEdge, Boolean strengthUseful, double meanStrength,
			Map<String, Situation> situations) {
	}

	private ReportCardMath() {
	}

	/** Natural timeframe per agent, in trading days. */
	public static int horizonFor(String agent) {
		String a = agent.toLowerCase(java.util.Locale.ROOT);
		if (a.contains("news") || a.contains("social") || a.contains("calendar") || a.contains("internet")) return 5;
		if (a.contains("technical")) return 10;
		if (a.contains("fundamental") || a.contains("academic")) return 60;
		return 20; // macro, filings, deep analysis, cause-of-move
	}

	/** Cluster key: the non-overlapping period this call's window falls in, plus its sector. */
	static String clusterKey(Obs o, int horizonDays) {
		long periodDays = Math.max(1, Math.round(horizonDays * 7.0 / 5.0));
		return (o.date().toEpochDay() / periodDays) + "|" + (o.sector() == null ? "?" : o.sector());
	}

	/** Grade a set of predictions on independent clusters. */
	public static Grade grade(List<Obs> obs, int horizonDays) {
		if (obs.isEmpty()) {
			return Grade.EMPTY;
		}
		Map<String, double[]> sums = new HashMap<>(); // key -> {sum, count}
		for (Obs o : obs) {
			double[] s = sums.computeIfAbsent(clusterKey(o, horizonDays), k -> new double[2]);
			s[0] += o.edge();
			s[1]++;
		}
		int n = sums.size();
		double mean = 0;
		int up = 0;
		double[] edges = new double[n];
		int i = 0;
		for (double[] s : sums.values()) {
			edges[i] = s[0] / s[1];
			mean += edges[i];
			if (edges[i] > 0) up++;
			i++;
		}
		mean /= n;
		double var = 0;
		for (double e : edges) var += (e - mean) * (e - mean);
		double sd = n > 1 ? Math.sqrt(var / (n - 1)) : 0;
		double t = sd == 0 ? 0 : mean / (sd / Math.sqrt(n));
		return new Grade(obs.size(), n, mean, t, (double) up / n);
	}

	/** Target weight for a grade (before the confidence check). */
	static double targetFor(Grade g) {
		if (g.tStat() >= GOOD_T && g.edge() > 0) {
			return 1.0 + Math.min(1.0, g.shrunkEdge() / 2.0);
		}
		if (g.tStat() <= BAD_T) {
			return 0.0;
		}
		return COIN_MULTIPLIER;
	}

	/** One step of at most {@link #STEP} from {@code current} toward {@code target}. */
	static double stepToward(double current, double target) {
		double d = Math.max(-STEP, Math.min(STEP, target - current));
		return Math.round((current + d) * 1000) / 1000.0;
	}

	/**
	 * Build an agent's card from its graded predictions.
	 *
	 * @param currentMultiplier the weight it has today
	 * @param currentSituations today's per-situation weights (absent keys start at the overall weight)
	 */
	public static Card card(String agent, List<Obs> obs, double currentMultiplier, Map<String, Double> currentSituations) {
		int h = horizonFor(agent);
		Grade overall = grade(obs, h);
		double meanStrength = obs.stream().mapToDouble(Obs::strength).average().orElse(0);

		// Confidence check: are its strong calls more often right than its weak ones?
		Double strongEdge = null;
		Double weakEdge = null;
		Boolean useful = null;
		if (obs.size() >= 2) {
			List<Obs> sorted = new ArrayList<>(obs);
			sorted.sort(Comparator.comparingDouble(Obs::strength));
			List<Obs> weak = sorted.subList(0, sorted.size() / 2);
			List<Obs> strong = sorted.subList(sorted.size() / 2, sorted.size());
			Grade gs = grade(strong, h);
			Grade gw = grade(weak, h);
			if (gs.clusters() >= SITUATION_MIN_CLUSTERS && gw.clusters() >= SITUATION_MIN_CLUSTERS) {
				strongEdge = gs.edge();
				weakEdge = gw.edge();
				useful = gs.edge() > gw.edge();
			}
		}

		Map<String, List<Obs>> bySituation = new LinkedHashMap<>();
		bySituation.put(RISK_OFF, new ArrayList<>());
		bySituation.put(CALM, new ArrayList<>());
		bySituation.put(EARNINGS, new ArrayList<>());
		for (Obs o : obs) {
			bySituation.get(RISK_OFF.equals(o.regime()) ? RISK_OFF : CALM).add(o);
			if (o.earningsWeek()) bySituation.get(EARNINGS).add(o);
		}

		if (overall.clusters() < MIN_CLUSTERS) {
			Map<String, Situation> sits = new LinkedHashMap<>();
			bySituation.forEach((k, v) -> sits.put(k, new Situation(grade(v, h), currentMultiplier,
					currentSituations.getOrDefault(k, currentMultiplier))));
			return new Card(agent, h, overall, State.UNGRADED, currentMultiplier, currentMultiplier, strongEdge, weakEdge,
					useful, meanStrength, sits);
		}

		double target = targetFor(overall);
		State state = target == 0.0 ? State.MUTED : target > 1.0 ? State.GOOD : State.COIN_FLIP;

		// Flip only when reliably wrong overall AND reliably wrong in both market moods (not just one bad stretch).
		Grade off = grade(bySituation.get(RISK_OFF), h);
		Grade calm = grade(bySituation.get(CALM), h);
		if (overall.tStat() <= FLIP_T && off.clusters() >= FLIP_SITUATION_MIN && calm.clusters() >= FLIP_SITUATION_MIN
				&& off.tStat() <= BAD_T && calm.tStat() <= BAD_T) {
			target = FLIP_MULTIPLIER;
			state = State.FLIPPED;
		}
		if (Boolean.FALSE.equals(useful) && target > 0) {
			target *= OVERCONFIDENCE_PENALTY;
		}
		double multiplier = stepToward(currentMultiplier, target);

		Map<String, Situation> sits = new LinkedHashMap<>();
		for (Map.Entry<String, List<Obs>> e : bySituation.entrySet()) {
			Grade g = grade(e.getValue(), h);
			// Blend toward the overall target until the situation has its own evidence.
			double w = g.clusters() / (g.clusters() + SHRINK_K);
			double sitTarget = state == State.FLIPPED ? target : w * targetFor(g) + (1 - w) * target;
			double sitCurrent = currentSituations.getOrDefault(e.getKey(), currentMultiplier);
			sits.put(e.getKey(), new Situation(g, sitTarget, stepToward(sitCurrent, sitTarget)));
		}
		return new Card(agent, h, overall, state, target, multiplier, strongEdge, weakEdge, useful, meanStrength, sits);
	}
}
