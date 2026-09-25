package com.argus.learning;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Finds patterns in the paper book's wins and losses — deterministically, and with the statistical
 * discipline a small, clustered sample demands. It never trusts a raw trade count:
 *
 * <ul>
 *   <li><b>Independent bets.</b> Trades are clustered by (ticker, entry day, direction): three horizon legs
 *       opened on one call are one bet, not three pieces of evidence. All statistics are over clusters.</li>
 *   <li><b>Cohorts vs. everyone else.</b> A cohort is every cluster whose situation tokens contain a set of
 *       predicates (one token, or a pair). It is compared with the <em>complement</em> by Welch's t-test, so a
 *       generally bad period does not make every cohort look bad.</li>
 *   <li><b>Held-out replication.</b> Clusters are split chronologically: patterns are found on the older
 *       {@code 1 - holdoutFraction} and must replicate — same direction — on the newer holdout before they are
 *       activated. A pattern that only fit the past is rejected, with the numbers that show why.</li>
 *   <li><b>Conservative by design.</b> Minimum cohort size, minimum effect, minimum |t| (stricter for boosts than
 *       penalties: it is better to warn than to encourage), redundancy pruning (a pair is dropped when a simpler
 *       rule already says the same), and a cap on new rules per run.</li>
 * </ul>
 */
public final class LessonMiner {

	private LessonMiner() {
	}

	/** One closed trade's outcome and the situation it was taken in. {@code excess} is percent vs SPY (or absolute if unbenchmarked). */
	public record Observation(Long tradeId, String ticker, String direction, Instant entryAt, Set<String> tokens, double excess) {
	}

	/** One independent bet: all the legs of a (ticker, day, direction) call, averaged. */
	public record Cluster(String key, Set<String> tokens, double excess, Instant at, int trades) {
	}

	/**
	 * @param minHoldoutDeltaPenalty how much worse than the rest a loser must still be on the held-out newer trades (points)
	 * @param minHoldoutDeltaBoost   how much better than the rest a winner must still be on the held-out trades — deliberately
	 *                               higher: it is better to warn readily than to encourage on thin evidence
	 */
	public record Config(int minClusters, double minEffectPts, double minTPenalty, double minTBoost, double holdoutFraction,
			int minHoldoutClusters, int maxTokenPool, int maxNewRules, int minTotalClusters, double minHoldoutDeltaPenalty,
			double minHoldoutDeltaBoost) {

		public static Config defaults() {
			return new Config(8, 1.5, 2.5, 3.0, 0.3, 3, 25, 6, 25, 1.0, 1.5);
		}
	}

	/** Statistics of a cohort against its complement over some set of clusters. */
	public record Stats(int clusters, int trades, double meanExcess, double winRate, double complementMean, double t) {
		public double delta() {
			return meanExcess - complementMean;
		}
	}

	public enum Decision { ACTIVATE, REJECT }

	/** A pattern that cleared the fit-window bar, with the verdict of the hold-out replication. */
	public record Candidate(Set<String> predicates, LearnedRule.Kind kind, double effect, Stats fit, Integer holdoutClusters,
			Double holdoutMean, Double holdoutComplementMean, Decision decision, String note) {
	}

	/** A cohort with its predicates, for reporting the worst and best situations. */
	public record Cohort(Set<String> predicates, Stats stats) {
	}

	public record Result(int totalClusters, int totalTrades, double baselineMean, double baselineWin, int fitClusters, int holdoutClusters,
			List<Candidate> candidates, List<Cohort> worstCohorts, List<Cohort> bestCohorts, String skipped) {
	}

	// ---- clustering ----

	public static List<Cluster> clusters(List<Observation> observations) {
		Map<String, List<Observation>> byKey = new LinkedHashMap<>();
		for (Observation o : observations) {
			String day = o.entryAt().toString().substring(0, 10);
			byKey.computeIfAbsent(o.ticker() + "|" + day + "|" + o.direction(), k -> new ArrayList<>()).add(o);
		}
		List<Cluster> out = new ArrayList<>();
		for (var e : byKey.entrySet()) {
			List<Observation> legs = e.getValue();
			Set<String> tokens = new LinkedHashSet<>(legs.get(0).tokens());
			for (Observation o : legs) {
				tokens.retainAll(o.tokens()); // only what every leg shares (e.g. the hold token differs between horizon legs)
			}
			out.add(new Cluster(e.getKey(), tokens, legs.stream().mapToDouble(Observation::excess).average().orElse(0),
					legs.stream().map(Observation::entryAt).min(Comparator.naturalOrder()).orElseThrow(), legs.size()));
		}
		out.sort(Comparator.comparing(Cluster::at));
		return out;
	}

	// ---- statistics ----

	static Stats stats(List<Cluster> all, Set<String> predicates) {
		double sumIn = 0, sumOut = 0, sqIn = 0, sqOut = 0;
		int nIn = 0, nOut = 0, wins = 0, tradesIn = 0;
		for (Cluster c : all) {
			if (c.tokens().containsAll(predicates)) {
				nIn++;
				sumIn += c.excess();
				sqIn += c.excess() * c.excess();
				tradesIn += c.trades();
				if (c.excess() > 0) wins++;
			}
			else {
				nOut++;
				sumOut += c.excess();
				sqOut += c.excess() * c.excess();
			}
		}
		double meanIn = nIn == 0 ? 0 : sumIn / nIn;
		double meanOut = nOut == 0 ? 0 : sumOut / nOut;
		double t = 0;
		if (nIn >= 2 && nOut >= 2) {
			double varIn = Math.max(0, (sqIn - nIn * meanIn * meanIn) / (nIn - 1));
			double varOut = Math.max(0, (sqOut - nOut * meanOut * meanOut) / (nOut - 1));
			double se = Math.sqrt(varIn / nIn + varOut / nOut);
			t = se == 0 ? 0 : (meanIn - meanOut) / se;
		}
		return new Stats(nIn, tradesIn, meanIn, nIn == 0 ? 0 : (double) wins / nIn, meanOut, t);
	}

	private static String key(String token) {
		int i = token.indexOf('=');
		return i < 0 ? token : token.substring(0, i);
	}

	// ---- mining ----

	public static Result mine(List<Observation> observations, Config cfg) {
		List<Cluster> clusters = clusters(observations);
		double baseMean = clusters.stream().mapToDouble(Cluster::excess).average().orElse(0);
		double baseWin = clusters.isEmpty() ? 0 : clusters.stream().filter(c -> c.excess() > 0).count() / (double) clusters.size();
		int totalTrades = observations.size();
		if (clusters.size() < cfg.minTotalClusters()) {
			return new Result(clusters.size(), totalTrades, baseMean, baseWin, 0, 0, List.of(), List.of(), List.of(),
					"Only " + clusters.size() + " independent bets so far — the learner needs at least " + cfg.minTotalClusters()
							+ " before it will draw any conclusion.");
		}
		int split = (int) Math.floor(clusters.size() * (1 - cfg.holdoutFraction()));
		List<Cluster> fit = clusters.subList(0, split);
		List<Cluster> holdout = clusters.subList(split, clusters.size());

		// token pool: most frequent tokens in the fit window (singles are always considered; pairs from the pool)
		Map<String, Integer> freq = new LinkedHashMap<>();
		fit.forEach(c -> c.tokens().forEach(t -> freq.merge(t, 1, Integer::sum)));
		List<String> singles = freq.entrySet().stream().filter(e -> e.getValue() >= cfg.minClusters()).map(Map.Entry::getKey).toList();
		List<String> pool = freq.entrySet().stream().filter(e -> e.getValue() >= cfg.minClusters() && !e.getKey().startsWith("ticker="))
				.sorted(Map.Entry.<String, Integer>comparingByValue().reversed()).limit(cfg.maxTokenPool()).map(Map.Entry::getKey).toList();

		List<Set<String>> predicateSets = new ArrayList<>();
		singles.forEach(s -> predicateSets.add(Set.of(s)));
		for (int i = 0; i < pool.size(); i++) {
			for (int j = i + 1; j < pool.size(); j++) {
				if (!key(pool.get(i)).equals(key(pool.get(j)))) {
					predicateSets.add(Set.of(pool.get(i), pool.get(j)));
				}
			}
		}

		record Scored(Set<String> predicates, Stats fit, boolean negative) {
		}
		List<Scored> scored = new ArrayList<>();
		List<Cohort> allStats = new ArrayList<>();
		for (Set<String> p : predicateSets) {
			Stats s = stats(fit, p);
			if (s.clusters() < cfg.minClusters()) continue;
			allStats.add(new Cohort(new TreeSet<>(p), s));
			boolean negative = s.meanExcess() < 0 && s.delta() <= -cfg.minEffectPts() && s.t() <= -cfg.minTPenalty();
			boolean positive = s.meanExcess() > 0 && s.delta() >= cfg.minEffectPts() && s.t() >= cfg.minTBoost() && s.winRate() >= 0.55;
			if (negative || positive) {
				scored.add(new Scored(new TreeSet<>(p), s, negative));
			}
		}
		scored.sort(Comparator.comparingDouble((Scored s) -> Math.abs(s.fit().t())).reversed());

		// Drop redundant rules: a candidate is dropped when another same-sign candidate's predicates are a proper subset of
		// its own — the simpler, more general rule already says it (and applies in more situations). Order-independent, so
		// a stronger-ranked superset can't shadow the general rule it contains.
		List<Scored> selected = new ArrayList<>();
		for (Scored c : scored) {
			boolean redundant = scored.stream().anyMatch(s -> s != c && s.negative() == c.negative()
					&& s.predicates().size() < c.predicates().size() && c.predicates().containsAll(s.predicates()));
			if (!redundant) selected.add(c);
		}

		List<Candidate> candidates = new ArrayList<>();
		int activated = 0;
		for (Scored c : selected) {
			Stats h = stats(holdout, c.predicates());
			Integer hClusters = h.clusters();
			boolean enough = h.clusters() >= cfg.minHoldoutClusters();
			double holdoutDelta = h.meanExcess() - h.complementMean();
			boolean sameSide = c.negative() ? h.meanExcess() < 0 && holdoutDelta <= -cfg.minHoldoutDeltaPenalty()
					: h.meanExcess() > 0 && holdoutDelta >= cfg.minHoldoutDeltaBoost();
			LearnedRule.Kind kind = kindFor(c.negative(), c.fit());
			double effect = effectFor(kind, c.fit());
			Decision decision;
			String note;
			if (!enough) {
				decision = Decision.REJECT;
				note = "Not enough newer trades to check it (" + h.clusters() + " held-out bets, need " + cfg.minHoldoutClusters() + ") — waiting for more data.";
			}
			else if (!sameSide) {
				decision = Decision.REJECT;
				note = String.format(Locale.ROOT, "Did not replicate on newer trades: held-out average %+.1f%% (rest %+.1f%%) over %d bets — needs to be at least %.1f points %s than the rest.",
						h.meanExcess(), h.complementMean(), h.clusters(), c.negative() ? cfg.minHoldoutDeltaPenalty() : cfg.minHoldoutDeltaBoost(),
						c.negative() ? "worse" : "better");
			}
			else if (activated >= cfg.maxNewRules()) {
				decision = Decision.REJECT;
				note = "Held-out replication passed, but this run's cap on new rules (" + cfg.maxNewRules() + ") was reached — it will be reconsidered next run.";
			}
			else {
				decision = Decision.ACTIVATE;
				activated++;
				note = String.format(Locale.ROOT, "Replicated on newer trades: held-out average %+.1f%% (rest %+.1f%%) over %d bets.",
						h.meanExcess(), h.complementMean(), h.clusters());
			}
			candidates.add(new Candidate(c.predicates(), kind, effect, c.fit(), hClusters, h.meanExcess(), h.complementMean(), decision, note));
		}

		List<Cohort> worst = general(allStats.stream().filter(c -> c.stats().meanExcess() < 0)
				.sorted(Comparator.comparingDouble((Cohort c) -> c.stats().delta())).toList(), 5);
		List<Cohort> best = general(allStats.stream().filter(c -> c.stats().meanExcess() > 0)
				.sorted(Comparator.comparingDouble((Cohort c) -> c.stats().delta()).reversed()).toList(), 5);
		return new Result(clusters.size(), totalTrades, baseMean, baseWin, fit.size(), holdout.size(), List.copyOf(candidates), worst, best, null);
	}

	/** The first {@code limit} cohorts, skipping any that merely restates a more general one already listed. */
	private static List<Cohort> general(List<Cohort> ranked, int limit) {
		List<Cohort> out = new ArrayList<>();
		for (Cohort c : ranked) {
			boolean restates = out.stream().anyMatch(o -> o.predicates().size() < c.predicates().size() && c.predicates().containsAll(o.predicates()));
			if (!restates) out.add(c);
			if (out.size() == limit) break;
		}
		return out;
	}

	/** BLOCK for a severe, consistent loser; otherwise PENALTY (loser) or BOOST (winner). */
	static LearnedRule.Kind kindFor(boolean negative, Stats s) {
		if (!negative) return LearnedRule.Kind.BOOST;
		return s.meanExcess() <= -4.0 && s.clusters() >= 12 && s.winRate() <= 0.35 ? LearnedRule.Kind.BLOCK : LearnedRule.Kind.PENALTY;
	}

	/** Points for PENALTY (2× the shortfall, 4-12) / BOOST (1.5× the edge, 3-8); 0 for BLOCK. */
	static double effectFor(LearnedRule.Kind kind, Stats s) {
		return switch (kind) {
			case PENALTY -> Math.max(4, Math.min(12, Math.round(Math.abs(s.delta()) * 2)));
			case BOOST -> Math.max(3, Math.min(8, Math.round(s.delta() * 1.5)));
			default -> 0;
		};
	}

	/**
	 * Re-check an already-active rule against all the data: still true? Returns the stats over every cluster.
	 * The caller retires the rule when it no longer holds.
	 */
	public static Stats recheck(List<Observation> observations, Set<String> predicates) {
		return stats(clusters(observations), predicates);
	}
}
