package com.argus.recommendation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.argus.deepanalysis.DeepVerdict;
import com.argus.deepanalysis.DeepView;
import com.argus.learning.LessonEffect;
import com.argus.learning.Lessons;
import com.argus.regime.MarketRegime;
import com.argus.regime.Sector;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The abstain rules, conviction score and hold-period logic that turn a raw 50/50-ish probability into a
 * call worth acting on. Uses the real scoring engine so the numbers are the ones production produces.
 */
class RecommendationPolicyTest {

	private final RecommendationPolicy policy = new RecommendationPolicy(Lessons.none());
	private final ProbabilityScoringEngine engine = new ProbabilityScoringEngine();

	private static AgentSignal sig(String agent, SignalDirection d, double w, int hintDays) {
		return new AgentSignal(agent, d, w, agent + " rationale", hintDays);
	}

	private static MarketRegime calm() {
		return new MarketRegime(Instant.now(), 0.2, 0.3, 15.0, -2.0, 0.5, Map.of());
	}

	private static MarketRegime selloff() {
		return new MarketRegime(Instant.now(), -2.1, -3.0, 31.0, 22.0, 1.0, Map.of());
	}

	private RecommendationPolicy.Verdict eval(MarketRegime regime, Double price, Double move1d,
			boolean earningsSoon, AgentSignal... signals) {
		List<AgentSignal> list = List.of(signals);
		return policy.evaluate(new RecommendationPolicy.Context("NVDA", engine.score(list), list, Sector.SEMICONDUCTORS,
				regime, price, move1d, earningsSoon, null, null));
	}

	private RecommendationPolicy.Verdict evalWith(RecommendationPolicy p, DeepView deep, AgentSignal... signals) {
		List<AgentSignal> list = List.of(signals);
		return p.evaluate(new RecommendationPolicy.Context("NVDA", engine.score(list), list, Sector.SEMICONDUCTORS,
				calm(), 150.0, 0.5, false, null, deep));
	}

	private static DeepView deep(DeepVerdict v, Integer hold) {
		return new DeepView(v, hold, 75, "Deep headline", "it closes below 90", 1);
	}

	private static AgentSignal[] solidBuy() {
		return new AgentSignal[] {sig("agent-1-news", SignalDirection.BULLISH, 0.7, 10), sig("agent-4-financial", SignalDirection.BULLISH, 0.7, 10),
				sig("agent-10-technical", SignalDirection.BULLISH, 0.6, 10)};
	}

	private RecommendationPolicy.Verdict eval(AgentSignal... signals) {
		return eval(calm(), 150.0, 0.5, false, signals);
	}

	// ---- the abstain option the old model lacked ----

	@Test
	void aLoneMacroSignalIsNotACall() {
		// The Sep 24 bug: ZGLD/XQQ/VFV/VDY were "bought" on one shared macro sentiment number.
		var v = eval(sig("agent-8-macro", SignalDirection.BULLISH, 0.35, 7));

		assertEquals(RecommendationAction.WATCH, v.action());
		assertFalse(v.action().actionable());
		assertTrue(v.thesis().contains("no hard evidence"), v.thesis());
	}

	@Test
	void crowdPlusMacroAloneIsNotACall() {
		var v = eval(sig("agent-2-social", SignalDirection.BULLISH, 0.3, 5),
				sig("agent-8-macro", SignalDirection.BULLISH, 0.4, 7));

		assertEquals(RecommendationAction.WATCH, v.action(), "soft evidence only — no news/insider/technical");
	}

	@Test
	void weakNewsPlusWeakCrowdIsStillNoCall() {
		var v = eval(sig("agent-1-news", SignalDirection.BULLISH, 0.35, 10),
				sig("agent-2-social", SignalDirection.BULLISH, 0.15, 5));

		assertEquals(RecommendationAction.WATCH, v.action());
		assertTrue(v.score() < RecommendationPolicy.ACTIONABLE_SCORE);
	}

	@Test
	void broadAgreeingEvidenceIsABuyWithAScoreAndAHoldPeriod() {
		var v = eval(sig("agent-1-news", SignalDirection.BULLISH, 0.6, 10),
				sig("agent-4-financial", SignalDirection.BULLISH, 0.7, 90),
				sig("agent-10-technical", SignalDirection.BULLISH, 0.5, 10));

		assertTrue(v.action() == RecommendationAction.BUY || v.action() == RecommendationAction.STRONG_BUY, v.toString());
		assertTrue(v.score() >= RecommendationPolicy.ACTIONABLE_SCORE);
		assertTrue(v.thesis().contains("days"), "the thesis must say how long to hold");
		assertFalse(v.reasons().isEmpty());
		assertTrue(v.exitPlan().contains("Re-check after"));
	}

	@Test
	void moreIndependentAgreeingSourcesScoreHigher() {
		var two = eval(sig("agent-1-news", SignalDirection.BULLISH, 0.6, 10),
				sig("agent-4-financial", SignalDirection.BULLISH, 0.6, 90));
		var three = eval(sig("agent-1-news", SignalDirection.BULLISH, 0.6, 10),
				sig("agent-4-financial", SignalDirection.BULLISH, 0.6, 90),
				sig("agent-10-technical", SignalDirection.BULLISH, 0.6, 10));

		assertTrue(three.score() > two.score());
	}

	@Test
	void bearishEvidenceProducesAnAvoidCall() {
		var v = eval(sig("agent-1-news", SignalDirection.BEARISH, 0.6, 10),
				sig("agent-4-financial", SignalDirection.BEARISH, 0.5, 30),
				sig("agent-10-technical", SignalDirection.BEARISH, 0.5, 10));

		assertTrue(v.action() == RecommendationAction.AVOID || v.action() == RecommendationAction.STRONG_AVOID);
	}

	@Test
	void disagreementLowersTheScoreAndIsDisclosed() {
		var agree = eval(sig("agent-1-news", SignalDirection.BULLISH, 0.6, 10),
				sig("agent-10-technical", SignalDirection.BULLISH, 0.6, 10));
		var conflicted = eval(sig("agent-1-news", SignalDirection.BULLISH, 0.6, 10),
				sig("agent-10-technical", SignalDirection.BULLISH, 0.6, 10),
				sig("agent-4-financial", SignalDirection.BEARISH, 0.5, 30));

		assertTrue(conflicted.score() < agree.score());
		assertTrue(conflicted.caveats().stream().anyMatch(c -> c.startsWith("Disagreement")));
	}

	// ---- guards ----

	@Test
	void pennyStocksAreNotCalled() {
		// RETO (~$1-3) alone lost $123 of the paper book's $217 loss.
		var v = eval(calm(), 2.5, 0.5, false,
				sig("agent-1-news", SignalDirection.BULLISH, 0.7, 10),
				sig("agent-4-financial", SignalDirection.BULLISH, 0.7, 90),
				sig("agent-10-technical", SignalDirection.BULLISH, 0.6, 10));

		assertEquals(RecommendationAction.WATCH, v.action());
		assertTrue(v.thesis().contains("sub-$5"), v.thesis());
	}

	@Test
	void wontSellIntoABroadSelloffOnHeadlinesAlone() {
		// The Trump-speech day: everything drops on a headline, then reverses. Bearish news + crowd is
		// exactly the evidence that would have "sold the bottom".
		var v = eval(selloff(), 150.0, -3.0, false,
				sig("agent-1-news", SignalDirection.BEARISH, 0.7, 10),
				sig("agent-2-social", SignalDirection.BEARISH, 0.3, 5),
				sig("agent-8-macro", SignalDirection.BEARISH, 0.4, 7));

		assertEquals(RecommendationAction.WATCH, v.action());
		assertTrue(v.thesis().contains("market-wide selloff"), v.thesis());
	}

	@Test
	void aSelloffDoesNotBlockBearishCallsBackedByInsidersAndTechnicals() {
		var v = eval(selloff(), 150.0, -3.0, false,
				sig("agent-1-news", SignalDirection.BEARISH, 0.7, 10),
				sig("agent-4-financial", SignalDirection.BEARISH, 0.7, 30),
				sig("agent-10-technical", SignalDirection.BEARISH, 0.6, 10));

		assertTrue(v.action().actionable(), v.thesis());
	}

	@Test
	void doesNotChaseAStockThatAlreadyJumped() {
		var v = eval(calm(), 150.0, 9.0, false,
				sig("agent-1-news", SignalDirection.BULLISH, 0.7, 10),
				sig("agent-4-financial", SignalDirection.BULLISH, 0.7, 90),
				sig("agent-10-technical", SignalDirection.BULLISH, 0.6, 10));

		assertEquals(RecommendationAction.WATCH, v.action());
		assertTrue(v.thesis().contains("chasing"), v.thesis());
	}

	@Test
	void buyingIntoWeaknessWithoutADipThesisCarriesAHeadwindPenalty() {
		AgentSignal[] s = {sig("agent-1-news", SignalDirection.BULLISH, 0.6, 10),
				sig("agent-4-financial", SignalDirection.BULLISH, 0.6, 90),
				sig("agent-10-technical", SignalDirection.BULLISH, 0.6, 10)};
		var normal = eval(calm(), 150.0, 0.5, false, s);
		var weak = eval(selloff(), 150.0, -1.0, false, s);

		assertEquals(normal.score() - 10, weak.score());
		assertTrue(weak.caveats().stream().anyMatch(c -> c.startsWith("Market headwind")));
	}

	@Test
	void aDipBuyThesisIsExemptFromTheHeadwindPenalty() {
		var v = eval(selloff(), 150.0, -4.0, false,
				sig("agent-11-cause", SignalDirection.BULLISH, 1.0, 14),
				sig("agent-10-technical", SignalDirection.BULLISH, 0.6, 10),
				sig("agent-1-news", SignalDirection.BULLISH, 0.4, 10));

		assertTrue(v.caveats().stream().noneMatch(c -> c.startsWith("Market headwind")));
		assertTrue(v.action().actionable(), v.thesis());
	}

	@Test
	void nearbyEarningsLowerTheScoreAndDisclose() {
		AgentSignal[] s = {sig("agent-1-news", SignalDirection.BULLISH, 0.6, 10),
				sig("agent-4-financial", SignalDirection.BULLISH, 0.6, 90),
				sig("agent-10-technical", SignalDirection.BULLISH, 0.6, 10)};
		var plain = eval(calm(), 150.0, 0.5, false, s);
		var earnings = eval(calm(), 150.0, 0.5, true, s);

		assertEquals(plain.score() - 10, earnings.score());
		assertTrue(earnings.caveats().stream().anyMatch(c -> c.startsWith("Earnings")));
	}

	@Test
	void missingMarketDataFailsOpen() {
		var v = eval(MarketRegime.unavailable(), null, null, false,
				sig("agent-1-news", SignalDirection.BULLISH, 0.6, 10),
				sig("agent-4-financial", SignalDirection.BULLISH, 0.7, 90),
				sig("agent-10-technical", SignalDirection.BULLISH, 0.6, 10));

		assertTrue(v.action().actionable(), "no regime/price data must not block or invent a guard");
	}

	// ---- hold period ----

	@Test
	void headlineDrivenCallsAreShortTerm() {
		assertEquals(7, RecommendationPolicy.holdDays(List.of(
				sig("agent-1-news", SignalDirection.BULLISH, 0.5, 10),
				sig("agent-2-social", SignalDirection.BULLISH, 0.3, 5)), false));
	}

	@Test
	void insiderBuyingPlusNewsIsMediumTerm() {
		assertEquals(30, RecommendationPolicy.holdDays(List.of(
				sig("agent-1-news", SignalDirection.BULLISH, 0.5, 10),
				sig("agent-4-financial", SignalDirection.BULLISH, 0.6, 90)), false));
	}

	@Test
	void strongInsiderBuyingAloneIsLongTerm() {
		assertEquals(90, RecommendationPolicy.holdDays(List.of(
				sig("agent-4-financial", SignalDirection.BULLISH, 0.8, 90)), false));
	}

	@Test
	void earningsForceAShortHold() {
		assertEquals(7, RecommendationPolicy.holdDays(List.of(
				sig("agent-4-financial", SignalDirection.BULLISH, 0.8, 90)), true));
	}

	@Test
	void noHintsDefaultsToShort() {
		assertEquals(7, RecommendationPolicy.holdDays(List.of(
				new AgentSignal("agent-1-news", SignalDirection.BULLISH, 0.5, "x")), false));
	}

	@Test
	void holdLabelsAreHumanReadable() {
		assertTrue(RecommendationPolicy.horizonLabel(7).startsWith("Short-term"));
		assertTrue(RecommendationPolicy.horizonLabel(30).startsWith("Medium-term"));
		assertTrue(RecommendationPolicy.horizonLabel(90).startsWith("Long-term"));
	}

	// ---- one LLM call must not saturate the score (live GOOGL: weak news + a dip call = 100/100) ----

	@Test
	void aSingleLlmDipCallCannotMakeAStrongBuy() {
		var v = eval(sig("agent-11-cause", SignalDirection.BULLISH, 1.275, 14),
				sig("agent-1-news", SignalDirection.BULLISH, 0.25, 10),
				sig("agent-2-social", SignalDirection.BULLISH, 0.2, 5),
				sig("agent-8-macro", SignalDirection.BULLISH, 0.07, 7));

		assertTrue(v.score() < RecommendationPolicy.STRONG_SCORE, "score " + v.score());
		assertTrue(v.action() != RecommendationAction.STRONG_BUY);
	}

	@Test
	void noSingleSignalContributesMoreThanTheCapToStrength() {
		// Cause at weight 1.5 and at 0.9 must score identically — the excess is not evidence.
		var capped = eval(sig("agent-11-cause", SignalDirection.BULLISH, 0.9, 14), sig("agent-1-news", SignalDirection.BULLISH, 0.5, 10));
		var huge = eval(sig("agent-11-cause", SignalDirection.BULLISH, 1.5, 14), sig("agent-1-news", SignalDirection.BULLISH, 0.5, 10));

		assertEquals(capped.score(), huge.score());
	}

	@Test
	void strongBuyRequiresThreeIndependentSourcesTwoOfThemSolid() {
		var v = eval(sig("agent-1-news", SignalDirection.BULLISH, 0.9, 10),
				sig("agent-4-financial", SignalDirection.BULLISH, 0.9, 90),
				sig("agent-10-technical", SignalDirection.BULLISH, 0.9, 10));

		assertEquals(RecommendationAction.STRONG_BUY, v.action(), "3 solid, agreeing, unopposed sources");

		var two = eval(sig("agent-1-news", SignalDirection.BULLISH, 0.9, 10),
				sig("agent-4-financial", SignalDirection.BULLISH, 0.9, 90));
		assertTrue(two.score() < RecommendationPolicy.STRONG_SCORE, "only two independent sources");
	}

	// ---- Agent 11 (deep analysis) participates ----

	@Test
	void aDeepNotWorthBuyingVetoesAQuickBuy() {
		var v = evalWith(policy, deep(DeepVerdict.NOT_WORTH_BUYING, null), solidBuy());

		assertEquals(RecommendationAction.WATCH, v.action());
		assertTrue(v.thesis().contains("Agent 11's deep analysis says this is not worth buying"), v.thesis());
	}

	@Test
	void aDeepWorthBuyingVetoesAQuickSell() {
		var v = evalWith(policy, deep(DeepVerdict.WORTH_BUYING, 30),
				sig("agent-1-news", SignalDirection.BEARISH, 0.7, 10), sig("agent-4-financial", SignalDirection.BEARISH, 0.7, 30),
				sig("agent-10-technical", SignalDirection.BEARISH, 0.6, 10));

		assertEquals(RecommendationAction.WATCH, v.action());
		assertTrue(v.thesis().contains("contradicting the sell signals"), v.thesis());
	}

	@Test
	void aDeepWaitIsACaveatNotAVeto() {
		var v = evalWith(policy, deep(DeepVerdict.WAIT, null), solidBuy());

		assertTrue(v.action().actionable());
		assertTrue(v.caveats().stream().anyMatch(c -> c.contains("Agent 11's deep analysis says wait")));
	}

	@Test
	void whenDeepAgreesOnABuyItsHoldPeriodReplacesTheQuickEstimate() {
		// Quick agents point to ~1 week (news/technical hints of 10 days), but Agent 11, having read the
		// fundamentals, recommends a 90-day position.
		var quick = evalWith(policy, null, solidBuy());
		var withDeep = evalWith(policy, deep(DeepVerdict.WORTH_BUYING, 90), solidBuy());

		assertEquals(7, quick.holdDays());
		assertEquals(90, withDeep.holdDays());
		assertTrue(withDeep.exitPlan().contains("Agent 11 would change its mind if: it closes below 90"));
	}

	@Test
	void earningsStillForceAShortHoldEvenWhenDeepWantsLong() {
		List<AgentSignal> list = List.of(solidBuy());
		var v = policy.evaluate(new RecommendationPolicy.Context("NVDA", engine.score(list), list, Sector.SEMICONDUCTORS, calm(), 150.0,
				0.5, true, null, deep(DeepVerdict.WORTH_BUYING, 90)));

		assertEquals(7, v.holdDays());
	}

	@Test
	void fundamentalsCountAsHardEvidenceSoAFundamentalsPlusNewsCallCanStand() {
		var v = evalWith(policy, null, sig("agent-12-fundamental", SignalDirection.BULLISH, 0.6, 90),
				sig("agent-1-news", SignalDirection.BULLISH, 0.6, 10), sig("agent-10-technical", SignalDirection.BULLISH, 0.6, 30));

		assertTrue(v.action().actionable(), v.thesis());
		assertTrue(v.thesis().contains("company fundamentals"));
	}

	// ---- learned lessons flow into the decision ----

	private static Lessons lessonsReturning(LessonEffect effect, java.util.concurrent.atomic.AtomicReference<java.util.Set<String>> seen) {
		return new Lessons() {
			@Override
			public LessonEffect evaluate(java.util.Set<String> tokens) {
				seen.set(tokens);
				return effect;
			}

			@Override
			public String promptSection(java.util.Set<String> tokens) {
				return "";
			}
		};
	}

	@Test
	void everyCallRecordsTheSituationItWasMadeInAsFeatureTokens() {
		var seen = new java.util.concurrent.atomic.AtomicReference<java.util.Set<String>>();
		var v = evalWith(new RecommendationPolicy(lessonsReturning(LessonEffect.none(), seen)), deep(DeepVerdict.WAIT, null), solidBuy());

		assertTrue(seen.get().containsAll(List.of("dir=BULLISH", "sector=SEMICONDUCTORS", "regime=NEUTRAL", "deep=WAIT", "ticker=NVDA",
				"has=NEWS", "has=INSIDER", "has=TECHNICAL", "price=50-200")), seen.get().toString());
		assertTrue(seen.get().stream().anyMatch(t -> t.startsWith("lead=")) && seen.get().stream().anyMatch(t -> t.startsWith("hold=")));
		assertEquals(seen.get(), v.features(), "the tokens the lessons saw are the ones stored for the learner");
	}

	@Test
	void aLearnedPenaltyLowersTheScoreAndIsShownOnTheCall() {
		var baseline = evalWith(policy, null, solidBuy());
		var fx = new LessonEffect(-8, null, null, 1.0, List.of(new LessonEffect.Applied(1L, "PENALTY", "Bullish calls in this setup lose", "14 independent bets, win rate 33%", 8)));

		var v = evalWith(new RecommendationPolicy(lessonsReturning(fx, new java.util.concurrent.atomic.AtomicReference<>())), null, solidBuy());

		assertEquals(baseline.score() - 8, v.score());
		assertEquals(1, v.learned().size());
		assertTrue(v.learned().get(0).startsWith("−8 conviction: Bullish calls in this setup lose"));
	}

	@Test
	void aLearnedBlockRuleTurnsACallIntoWatchWithTheLessonAsTheReason() {
		var fx = new LessonEffect(0, "chasing crowd-led bullish calls in a selloff has lost money", null, 1.0,
				List.of(new LessonEffect.Applied(2L, "BLOCK", "chasing crowd-led bullish calls in a selloff has lost money", "12 independent bets", 0)));

		var v = evalWith(new RecommendationPolicy(lessonsReturning(fx, new java.util.concurrent.atomic.AtomicReference<>())), null, solidBuy());

		assertEquals(RecommendationAction.WATCH, v.action());
		assertTrue(v.thesis().contains("a lesson learned from past trades blocks it"), v.thesis());
		assertTrue(v.learned().get(0).startsWith("Blocked:"));
	}

	@Test
	void aLearnedHoldCapShortensTheHoldingPeriod() {
		var fx = new LessonEffect(0, null, 10, 1.0, List.of(new LessonEffect.Applied(3L, "CAP_HOLD", "long holds in this setup give back gains", "9 bets", 10)));

		var v = evalWith(new RecommendationPolicy(lessonsReturning(fx, new java.util.concurrent.atomic.AtomicReference<>())),
				deep(DeepVerdict.WORTH_BUYING, 90), solidBuy());

		assertEquals(7, v.holdDays());
	}

	@Test
	void aLearnedBoostRaisesScoreButCannotMakeAThinCallStrong() {
		var fx = new LessonEffect(10, null, null, 1.0, List.of(new LessonEffect.Applied(4L, "BOOST", "this setup has worked", "10 bets", 10)));
		var two = new AgentSignal[] {sig("agent-1-news", SignalDirection.BULLISH, 0.9, 10), sig("agent-4-financial", SignalDirection.BULLISH, 0.9, 30)};

		var v = evalWith(new RecommendationPolicy(lessonsReturning(fx, new java.util.concurrent.atomic.AtomicReference<>())), null, two);

		assertTrue(v.score() < RecommendationPolicy.STRONG_SCORE, "two independent sources can never reach 'strong', boost or not");
	}

	// ---- thesis tracking, filings and valuation context ----

	@Test
	void anAtRiskDeepVerdictCostsConvictionAddsACaveatAndNoLongerSetsTheHold() {
		var healthy = evalWith(policy, deep(DeepVerdict.WORTH_BUYING, 90), solidBuy());
		var atRisk = evalWith(policy, new DeepView(DeepVerdict.WORTH_BUYING, 90, 75, "Deep headline", "it closes below 90", 1, true,
				"Price fell through 90.", 90.0), solidBuy());

		assertEquals(healthy.score() - 10, atRisk.score(), 1.0);
		assertTrue(atRisk.caveats().stream().anyMatch(c -> c.contains("at risk") && c.contains("Price fell through 90.")), atRisk.caveats().toString());
		assertEquals(7, atRisk.holdDays(), "a verdict under review must not stretch the hold to its 90 days");
		assertEquals(90, healthy.holdDays());
	}

	@Test
	void guidanceAndValuationBecomeFeatureTokensSoTheLearnerCanMineThem() {
		List<AgentSignal> list = List.of(solidBuy());
		var v = policy.evaluate(new RecommendationPolicy.Context("NVDA", engine.score(list), list, Sector.SEMICONDUCTORS, calm(), 150.0, 0.5, false,
				null, null, new RecommendationPolicy.Standing("LOWERED", "RICH")));

		assertTrue(v.features().contains("guidance=LOWERED"));
		assertTrue(v.features().contains("val=RICH"));
		assertTrue(evalWith(policy, null, solidBuy()).features().stream().noneMatch(t -> t.startsWith("guidance=") || t.startsWith("val=")));
	}

	@Test
	void filingsEvidenceIsHardAndAddsItsOwnReCheckTrigger() {
		var v = evalWith(policy, null, sig("agent-14-filings", SignalDirection.BULLISH, 0.7, 30), sig("agent-1-news", SignalDirection.BULLISH, 0.7, 10),
				sig("agent-10-technical", SignalDirection.BULLISH, 0.6, 10));

		assertTrue(v.action().actionable(), v.toString());
		assertTrue(v.exitPlan().contains("earnings release or filing disappoints"), v.exitPlan());
		assertTrue(v.features().contains("has=FILINGS"));
	}
}
