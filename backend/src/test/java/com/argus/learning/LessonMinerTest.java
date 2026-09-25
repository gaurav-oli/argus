package com.argus.learning;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.argus.learning.LessonMiner.Candidate;
import com.argus.learning.LessonMiner.Config;
import com.argus.learning.LessonMiner.Observation;
import com.argus.learning.LessonMiner.Result;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.function.IntFunction;
import org.junit.jupiter.api.Test;

/** The miner must find real patterns, reject ones that only fit the past, and stay quiet when there is nothing there. */
class LessonMinerTest {

	private static final Instant BASE = Instant.parse("2026-07-01T00:00:00Z");

	/** i-th independent bet: unique ticker, one day apart, tokens and excess supplied by the scenario. */
	private static List<Observation> series(int n, long seed, IntFunction<Set<String>> tokens, IntFunction<double[]> meanSd) {
		Random rnd = new Random(seed);
		List<Observation> out = new ArrayList<>();
		for (int i = 0; i < n; i++) {
			double[] ms = meanSd.apply(i);
			out.add(new Observation((long) i, "T" + i, "BULLISH", BASE.plusSeconds(86_400L * i), tokens.apply(i), ms[0] + ms[1] * rnd.nextGaussian()));
		}
		return out;
	}

	private static boolean social(int i) {
		return i % 3 == 0; // a third of bets are crowd-led, spread evenly through time
	}

	private static Set<String> tokens(int i) {
		return social(i) ? Set.of("dir=BULLISH", "has=SOCIAL", "lead=SOCIAL", "sector=TECH") : Set.of("dir=BULLISH", "has=NEWS", "lead=NEWS", "sector=TECH");
	}

	@Test
	void tooFewIndependentBetsMeansNoConclusionsAtAll() {
		Result r = LessonMiner.mine(series(10, 1, LessonMinerTest::tokens, i -> new double[] {-3, 1}), Config.defaults());

		assertTrue(r.candidates().isEmpty());
		assertTrue(r.skipped().contains("needs at least 25"), r.skipped());
	}

	@Test
	void aRealPatternThatHoldsInOlderAndNewerTradesIsActivated() {
		// Crowd-led bets lose ~4% vs the market throughout; news-led bets are flat. Both eras contain both.
		List<Observation> obs = series(120, 42, LessonMinerTest::tokens, i -> social(i) ? new double[] {-4.0, 1.5} : new double[] {0.3, 1.5});

		Result r = LessonMiner.mine(obs, Config.defaults());

		Candidate c = r.candidates().stream().filter(x -> x.predicates().contains("has=SOCIAL") || x.predicates().contains("lead=SOCIAL"))
				.findFirst().orElseThrow();
		assertEquals(LessonMiner.Decision.ACTIVATE, c.decision(), c.note());
		assertTrue(c.kind() == LearnedRule.Kind.PENALTY || c.kind() == LearnedRule.Kind.BLOCK);
		assertTrue(c.fit().meanExcess() < -3 && c.fit().t() < -2.5);
		assertNotNull(c.holdoutMean());
		assertTrue(c.holdoutMean() < 0, "replicated on the held-out newer trades");
		assertTrue(c.note().startsWith("Replicated"));
	}

	@Test
	void aSevereConsistentLoserBecomesABlockWhileAMilderOneIsAPenalty() {
		Result severe = LessonMiner.mine(series(150, 7, LessonMinerTest::tokens, i -> social(i) ? new double[] {-6.0, 1.5} : new double[] {0.3, 1.5}),
				Config.defaults());
		Result mild = LessonMiner.mine(series(150, 7, LessonMinerTest::tokens, i -> social(i) ? new double[] {-2.5, 1.0} : new double[] {0.3, 1.0}),
				Config.defaults());

		assertTrue(severe.candidates().stream().anyMatch(c -> c.kind() == LearnedRule.Kind.BLOCK && c.decision() == LessonMiner.Decision.ACTIVATE));
		assertTrue(mild.candidates().stream().anyMatch(c -> c.kind() == LearnedRule.Kind.PENALTY && c.decision() == LessonMiner.Decision.ACTIVATE));
		assertTrue(mild.candidates().stream().noneMatch(c -> c.kind() == LearnedRule.Kind.BLOCK));
	}

	@Test
	void aPatternThatOnlyFitTheOlderTradesIsRejectedWithTheNumbersThatShowWhy() {
		// Crowd-led bets lost badly early on but in the newer 30% they did fine — a regime that faded.
		List<Observation> obs = series(120, 3, LessonMinerTest::tokens,
				i -> social(i) ? (i < 84 ? new double[] {-4.5, 1.5} : new double[] {1.5, 1.5}) : new double[] {0.3, 1.5});

		Result r = LessonMiner.mine(obs, Config.defaults());

		Candidate c = r.candidates().stream().filter(x -> x.predicates().contains("has=SOCIAL") || x.predicates().contains("lead=SOCIAL"))
				.findFirst().orElseThrow();
		assertEquals(LessonMiner.Decision.REJECT, c.decision());
		assertTrue(c.note().startsWith("Did not replicate on newer trades"), c.note());
	}

	@Test
	void aGenerallyBadPeriodDoesNotCreateRulesForArbitraryCohorts() {
		// Everything loses ~3% regardless of setup. That is the market, not a pattern — the complement is just as bad.
		Result r = LessonMiner.mine(series(150, 11, LessonMinerTest::tokens, i -> new double[] {-3.0, 1.5}), Config.defaults());

		assertTrue(r.candidates().isEmpty(), "no cohort differs from the rest, so nothing may be proposed: " + r.candidates());
		assertTrue(r.baselineMean() < -2.5);
	}

	@Test
	void pureNoiseProducesNoActiveRules() {
		Result r = LessonMiner.mine(series(200, 99, LessonMinerTest::tokens, i -> new double[] {0.0, 2.0}), Config.defaults());

		assertTrue(r.candidates().stream().noneMatch(c -> c.decision() == LessonMiner.Decision.ACTIVATE));
	}

	@Test
	void aPairThatAddsNothingOverASingleTokenRuleIsPrunedAsRedundant() {
		List<Observation> obs = series(150, 5, LessonMinerTest::tokens, i -> social(i) ? new double[] {-4.0, 1.2} : new double[] {0.3, 1.2});

		Result r = LessonMiner.mine(obs, Config.defaults());

		// has=SOCIAL alone explains it; "has=SOCIAL + dir=BULLISH" (same cohort) must not appear as a second rule.
		long socialRules = r.candidates().stream().filter(c -> c.predicates().stream().anyMatch(t -> t.endsWith("=SOCIAL"))).count();
		assertTrue(socialRules <= 2, "redundant supersets are pruned; got " + r.candidates());
		assertTrue(r.candidates().stream().noneMatch(c -> c.predicates().size() == 2 && c.predicates().contains("dir=BULLISH")
				&& c.predicates().stream().anyMatch(t -> t.endsWith("=SOCIAL"))));
	}

	@Test
	void aWinningPatternIsABoostOnlyWithStrongerEvidenceAndAHighWinRate() {
		List<Observation> strong = series(150, 21, LessonMinerTest::tokens, i -> social(i) ? new double[] {0.3, 1.2} : new double[] {3.5, 1.2});
		Result r = LessonMiner.mine(strong, Config.defaults());
		Candidate boost = r.candidates().stream().filter(c -> c.kind() == LearnedRule.Kind.BOOST).findFirst().orElseThrow();
		assertTrue(boost.effect() >= 3 && boost.effect() <= 8, "boosts are small: " + boost.effect());
		assertEquals(LessonMiner.Decision.ACTIVATE, boost.decision());

		// Boosts have a stricter bar than penalties: with the boost t-threshold raised, the same winning
		// pattern earns nothing while an equally strong losing pattern is still penalised.
		Config strictBoost = new Config(8, 1.5, 2.5, 100.0, 0.3, 3, 25, 6, 25, 1.0, 1.5);
		assertTrue(LessonMiner.mine(strong, strictBoost).candidates().stream().noneMatch(c -> c.kind() == LearnedRule.Kind.BOOST));
		List<Observation> losing = series(150, 21, LessonMinerTest::tokens, i -> social(i) ? new double[] {-3.5, 1.2} : new double[] {0.3, 1.2});
		assertTrue(LessonMiner.mine(losing, strictBoost).candidates().stream().anyMatch(c -> c.decision() == LessonMiner.Decision.ACTIVATE),
				"the penalty side is unaffected by the boost threshold");
	}

	@Test
	void legsOfOneCallAreOneIndependentBetNotThree() {
		List<Observation> obs = new ArrayList<>();
		for (int leg : new int[] {7, 30, 90}) {
			obs.add(new Observation((long) leg, "NVDA", "BULLISH", BASE, Set.of("dir=BULLISH", "hold=" + leg, "sector=SEMI"), leg == 7 ? -3.0 : -1.0));
		}
		obs.add(new Observation(9L, "AMD", "BULLISH", BASE, Set.of("dir=BULLISH", "hold=7", "sector=SEMI"), 2.0));

		List<LessonMiner.Cluster> clusters = LessonMiner.clusters(obs);

		assertEquals(2, clusters.size(), "NVDA's three horizon legs are one bet; AMD is another");
		LessonMiner.Cluster nvda = clusters.stream().filter(c -> c.key().startsWith("NVDA")).findFirst().orElseThrow();
		assertEquals(3, nvda.trades());
		assertEquals((-3.0 - 1.0 - 1.0) / 3, nvda.excess(), 1e-9, "the legs' returns are averaged");
		assertTrue(nvda.tokens().contains("sector=SEMI") && nvda.tokens().stream().noneMatch(t -> t.startsWith("hold=")),
				"only tokens every leg shares survive clustering");
	}

	@Test
	void theNumberOfNewRulesPerRunIsCapped() {
		// Several distinct losing sectors, all replicating; the cap must limit activations.
		String[] sectors = {"A", "B", "C", "D", "E", "F", "G", "H"};
		List<Observation> obs = series(320, 8, i -> Set.of("dir=BULLISH", "sector=" + sectors[i % 8]),
				i -> (i % 8) < 6 ? new double[] {-5.0, 1.0} : new double[] {1.0, 1.0});

		Result r = LessonMiner.mine(obs, new Config(8, 1.5, 2.5, 3.0, 0.3, 3, 25, 2, 25, 1.0, 1.5));

		assertEquals(2, r.candidates().stream().filter(c -> c.decision() == LessonMiner.Decision.ACTIVATE).count());
		assertTrue(r.candidates().stream().anyMatch(c -> c.note().contains("cap on new rules")));
	}

	@Test
	void recheckReportsHowARuleIsDoingOnAllTheData() {
		List<Observation> obs = series(90, 4, LessonMinerTest::tokens, i -> social(i) ? new double[] {-4.0, 1.0} : new double[] {0.3, 1.0});

		LessonMiner.Stats s = LessonMiner.recheck(obs, Set.of("has=SOCIAL"));

		assertTrue(s.meanExcess() < -3 && s.t() < -5);
	}

	@Test
	void aBoostNeedsAStrongerHeldOutEdgeThanAPenaltyNeedsAHeldOutLoss() {
		// A winning pattern whose newer trades are only marginally better than the rest is NOT enough to encourage trading it.
		List<Observation> obs = series(150, 31, LessonMinerTest::tokens,
				i -> social(i) ? new double[] {0.3, 1.2} : (i < 105 ? new double[] {3.5, 1.2} : new double[] {0.9, 1.2}));

		Result r = LessonMiner.mine(obs, Config.defaults());

		Candidate boost = r.candidates().stream().filter(c -> c.kind() == LearnedRule.Kind.BOOST).findFirst().orElseThrow();
		assertEquals(LessonMiner.Decision.REJECT, boost.decision(), "held-out edge was a fraction of the fit edge");
		assertTrue(boost.note().contains("at least 1.5 points better than the rest"), boost.note());
	}

	@Test
	void aGeneralRuleShadowsItsMoreSpecificSupersetsRegardlessOfRanking() {
		// "under10" alone loses; so does "bullish + under10" (every trade is bullish). Only the general rule should survive.
		List<Observation> obs = series(150, 17, i -> i % 4 == 0 ? Set.of("dir=BULLISH", "price=under10", "sector=TECH") : Set.of("dir=BULLISH", "price=50-200", "sector=TECH"),
				i -> i % 4 == 0 ? new double[] {-8.0, 1.5} : new double[] {0.2, 1.5});

		Result r = LessonMiner.mine(obs, Config.defaults());

		List<Candidate> losers = r.candidates().stream().filter(c -> c.kind() != LearnedRule.Kind.BOOST).toList();
		assertTrue(losers.stream().anyMatch(c -> c.predicates().equals(Set.of("price=under10"))), losers.toString());
		assertTrue(losers.stream().noneMatch(c -> c.predicates().size() > 1 && c.predicates().contains("price=under10")),
				"the superset must be pruned: " + losers);
	}

	@Test
	void theWorstAndBestListsDoNotRepeatTheSameFindingAsSupersets() {
		List<Observation> obs = series(150, 17, i -> i % 4 == 0 ? Set.of("dir=BULLISH", "price=under10", "sector=TECH") : Set.of("dir=BULLISH", "price=50-200", "sector=TECH"),
				i -> i % 4 == 0 ? new double[] {-8.0, 1.5} : new double[] {0.2, 1.5});

		Result r = LessonMiner.mine(obs, Config.defaults());

		assertTrue(r.worstCohorts().stream().anyMatch(c -> c.predicates().equals(Set.of("price=under10"))));
		assertTrue(r.worstCohorts().stream().noneMatch(c -> c.predicates().size() > 1 && c.predicates().contains("price=under10")),
				"restatements of a more general cohort are not listed: " + r.worstCohorts());
	}
}
