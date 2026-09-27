package com.argus.deepanalysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.argus.deepanalysis.DeepVerdictGuard.Draft;
import com.argus.deepanalysis.DeepVerdictGuard.Input;
import com.argus.deepanalysis.DeepVerdictGuard.Result;
import com.argus.deepanalysis.DeepVerdictGuard.Specialist;
import com.argus.deepanalysis.DeepVerdictGuard.Stance;
import com.argus.learning.LessonEffect;
import java.util.List;
import org.junit.jupiter.api.Test;

/** "The LLM proposes, the data disposes": the guard may only downgrade or cap, never upgrade or invent. */
class DeepVerdictGuardTest {

	private static Specialist s(String name, Stance stance, double strength) {
		return new Specialist(name, stance, strength, 1.0);
	}

	private static Input input(List<Specialist> sp, double skeptic, boolean fundApplicable, Double fundScore, boolean etf,
			Double price, Integer earnings, boolean chart) {
		return new Input(sp, skeptic, fundApplicable, fundScore, etf, price, earnings, chart, null);
	}

	private static Input withLessons(Input base, LessonEffect fx) {
		return new Input(base.specialists(), base.skepticSeverity(), base.fundamentalsApplicable(), base.fundamentalScore(), base.isEtf(),
				base.lastPrice(), base.earningsInTradingDays(), base.chartAvailable(), fx);
	}

	private static Input bullish() {
		return input(List.of(s("technical", Stance.BULLISH, 0.7), s("fundamental", Stance.BULLISH, 0.8), s("catalyst", Stance.BULLISH, 0.6),
				s("macro", Stance.NEUTRAL, 0.3)), 0.2, true, 0.5, false, 150.0, null, true);
	}

	@Test
	void aSupportedBuyStaysABuyWithTheHoldSnapped() {
		Result r = DeepVerdictGuard.apply(new Draft(DeepVerdict.WORTH_BUYING, 25, 80), bullish());

		assertEquals(DeepVerdict.WORTH_BUYING, r.verdict());
		assertEquals(30, r.holdDays(), "25 days snaps to the 30-day bucket");
		assertTrue(r.consensus() >= 0.15);
	}

	@Test
	void aBuyWithNoAnalystSupportIsDowngradedToWait() {
		Input weak = input(List.of(s("technical", Stance.NEUTRAL, 0.2), s("fundamental", Stance.BULLISH, 0.1),
				s("catalyst", Stance.NEUTRAL, 0.2)), 0.0, true, 0.1, false, 150.0, null, true);

		Result r = DeepVerdictGuard.apply(new Draft(DeepVerdict.WORTH_BUYING, 30, 85), weak);

		assertEquals(DeepVerdict.WAIT, r.verdict());
		assertNull(r.holdDays(), "only a buy has a holding period");
		assertTrue(r.notes().get(0).contains("Downgraded to WAIT"));
		assertTrue(r.conviction() <= 50, "a downgrade cannot keep a high conviction");
	}

	@Test
	void theSkepticReducesTheConsensus() {
		double without = DeepVerdictGuard.consensus(bullish().specialists(), 0.0);
		double with = DeepVerdictGuard.consensus(bullish().specialists(), 1.0);

		assertTrue(with < without);
		assertEquals(without - 0.2, with, 1e-9);
	}

	@Test
	void aSplitDeskWithTwoStronglyBearishSpecialistsDowngradesABuy() {
		Input split = input(List.of(s("technical", Stance.BULLISH, 1.0), s("fundamental", Stance.BULLISH, 1.0),
				s("catalyst", Stance.BEARISH, 0.7), s("macro", Stance.BEARISH, 0.7), s("x", Stance.BULLISH, 1.0),
				s("y", Stance.BULLISH, 1.0)), 0.0, true, 0.5, false, 150.0, null, true);

		Result r = DeepVerdictGuard.apply(new Draft(DeepVerdict.WORTH_BUYING, 30, 80), split);

		assertEquals(DeepVerdict.WAIT, r.verdict());
		assertTrue(r.notes().stream().anyMatch(n -> n.contains("split")));
	}

	@Test
	void pennyStocksAreNeverWorthBuying() {
		Input penny = input(bullish().specialists(), 0.0, true, 0.5, false, 2.5, null, true);

		Result r = DeepVerdictGuard.apply(new Draft(DeepVerdict.WORTH_BUYING, 30, 80), penny);

		assertEquals(DeepVerdict.WAIT, r.verdict());
		assertTrue(r.notes().stream().anyMatch(n -> n.contains("sub-$5")));
	}

	@Test
	void earningsWithinThreeTradingDaysForceWait() {
		Input e = input(bullish().specialists(), 0.0, true, 0.5, false, 150.0, 2, true);

		assertEquals(DeepVerdict.WAIT, DeepVerdictGuard.apply(new Draft(DeepVerdict.WORTH_BUYING, 30, 80), e).verdict());
	}

	@Test
	void earningsWithinTenTradingDaysCostConvictionAndCapTheHold() {
		Input e = input(bullish().specialists(), 0.0, true, 0.5, false, 150.0, 8, true);
		Input none = input(bullish().specialists(), 0.0, true, 0.5, false, 150.0, null, true);

		Result near = DeepVerdictGuard.apply(new Draft(DeepVerdict.WORTH_BUYING, 90, 70), e);
		Result far = DeepVerdictGuard.apply(new Draft(DeepVerdict.WORTH_BUYING, 90, 70), none);

		assertEquals(far.conviction() - 10, near.conviction());
		assertEquals(30, near.holdDays(), "no 90-day hold straight into an earnings release");
	}

	@Test
	void aWeakNotWorthBuyingIsSoftenedToWait() {
		Input mild = input(List.of(s("technical", Stance.BEARISH, 0.1), s("fundamental", Stance.NEUTRAL, 0.2)), 0.0, true, -0.1, false,
				150.0, null, true);

		Result r = DeepVerdictGuard.apply(new Draft(DeepVerdict.NOT_WORTH_BUYING, null, 70), mild);

		assertEquals(DeepVerdict.WAIT, r.verdict());
	}

	@Test
	void aFirmAvoidWithBearishConsensusIsKept() {
		Input bear = input(List.of(s("technical", Stance.BEARISH, 0.8), s("fundamental", Stance.BEARISH, 0.9),
				s("catalyst", Stance.BEARISH, 0.6)), 0.0, true, -0.5, false, 150.0, null, true);

		Result r = DeepVerdictGuard.apply(new Draft(DeepVerdict.NOT_WORTH_BUYING, null, 80), bear);

		assertEquals(DeepVerdict.NOT_WORTH_BUYING, r.verdict());
		assertNull(r.holdDays());
	}

	@Test
	void aWaitIsNeverUpgradedEvenWhenTheConsensusIsStrong() {
		Result r = DeepVerdictGuard.apply(new Draft(DeepVerdict.WAIT, null, 40), bullish());

		assertEquals(DeepVerdict.WAIT, r.verdict(), "the guard only ever downgrades");
	}

	@Test
	void convictionCannotExceedWhatTheConsensusSupports() {
		Input modest = input(List.of(s("technical", Stance.BULLISH, 0.25), s("fundamental", Stance.BULLISH, 0.25),
				s("catalyst", Stance.NEUTRAL, 0.1)), 0.0, true, 0.3, false, 150.0, null, true);

		Result r = DeepVerdictGuard.apply(new Draft(DeepVerdict.WORTH_BUYING, 30, 95), modest);

		assertEquals(DeepVerdict.WORTH_BUYING, r.verdict());
		assertTrue(r.conviction() <= 40 + 60 * (r.consensus() / 0.6) + 1, "conviction " + r.conviction() + " consensus " + r.consensus());
		assertTrue(r.conviction() < 95);
	}

	@Test
	void anEtfIsCappedAt70AndMissingFundamentalsAt65AndNoChartAt60() {
		Input strong = input(List.of(s("technical", Stance.BULLISH, 1.0), s("catalyst", Stance.BULLISH, 1.0),
				new Specialist("fundamental", Stance.NEUTRAL, 0, 0)), 0.0, false, null, true, 100.0, null, true);
		assertEquals(70, DeepVerdictGuard.apply(new Draft(DeepVerdict.WORTH_BUYING, 30, 99), strong).conviction());

		Input stock = input(strong.specialists(), 0.0, false, null, false, 100.0, null, true);
		assertEquals(65, DeepVerdictGuard.apply(new Draft(DeepVerdict.WORTH_BUYING, 30, 99), stock).conviction());

		Input noChart = input(strong.specialists(), 0.0, false, null, true, 100.0, null, false);
		assertEquals(60, DeepVerdictGuard.apply(new Draft(DeepVerdict.WORTH_BUYING, 30, 99), noChart).conviction());
	}

	@Test
	void aNinetyDayHoldNeedsSupportiveFundamentals() {
		Input noFund = input(List.of(s("technical", Stance.BULLISH, 0.9), s("catalyst", Stance.BULLISH, 0.9),
				new Specialist("fundamental", Stance.NEUTRAL, 0, 0)), 0.0, false, null, true, 100.0, null, true);
		Input goodFund = bullish();

		assertEquals(30, DeepVerdictGuard.apply(new Draft(DeepVerdict.WORTH_BUYING, 90, 80), noFund).holdDays());
		assertEquals(90, DeepVerdictGuard.apply(new Draft(DeepVerdict.WORTH_BUYING, 90, 80), goodFund).holdDays());
	}

	@Test
	void aMissingHoldDefaultsToThirtyDaysAndIsDisclosed() {
		Result r = DeepVerdictGuard.apply(new Draft(DeepVerdict.WORTH_BUYING, null, 70), bullish());

		assertEquals(30, r.holdDays());
		assertTrue(r.notes().stream().anyMatch(n -> n.contains("defaulted to 30")));
	}

	@Test
	void holdSnappingUsesGeometricMidpoints() {
		assertEquals(7, DeepVerdictGuard.snapHold(10));
		assertEquals(7, DeepVerdictGuard.snapHold(14));
		assertEquals(30, DeepVerdictGuard.snapHold(15));
		assertEquals(30, DeepVerdictGuard.snapHold(52));
		assertEquals(90, DeepVerdictGuard.snapHold(53));
		assertEquals(90, DeepVerdictGuard.snapHold(180));
		assertEquals(30, DeepVerdictGuard.snapHold(null));
	}

	@Test
	void aSpecialistWithNothingToSayIsExcludedNotCountedAsNeutral() {
		double onlyTwo = DeepVerdictGuard.consensus(List.of(s("technical", Stance.BULLISH, 0.8), s("catalyst", Stance.BULLISH, 0.6)), 0);
		double withAbsent = DeepVerdictGuard.consensus(List.of(s("technical", Stance.BULLISH, 0.8), s("catalyst", Stance.BULLISH, 0.6),
				new Specialist("fundamental", Stance.NEUTRAL, 0, 0)), 0);

		assertEquals(onlyTwo, withAbsent, 1e-9);
	}

	// ---- learned lessons act on the verdict ----

	private static LessonEffect effect(int delta, String block, Integer holdCap) {
		return new LessonEffect(delta, block, holdCap, 1.0, List.of(new LessonEffect.Applied(1L, delta >= 0 ? "BOOST" : "PENALTY",
				"Bullish calls in this setup lose", "14 bets", Math.abs(delta))));
	}

	@Test
	void aMatchingLessonBlockDowngradesABuyToWait() {
		Result r = DeepVerdictGuard.apply(new Draft(DeepVerdict.WORTH_BUYING, 30, 80),
				withLessons(bullish(), effect(0, "chasing crowd-led calls in a selloff has lost money", null)));

		assertEquals(DeepVerdict.WAIT, r.verdict());
		assertTrue(r.notes().get(0).contains("a lesson learned from past trades blocks buying"));
		assertTrue(r.conviction() <= 50);
	}

	@Test
	void aLessonPenaltyReducesConvictionAndIsDisclosed() {
		Result plain = DeepVerdictGuard.apply(new Draft(DeepVerdict.WORTH_BUYING, 30, 70), bullish());
		Result penalised = DeepVerdictGuard.apply(new Draft(DeepVerdict.WORTH_BUYING, 30, 70), withLessons(bullish(), effect(-8, null, null)));

		assertEquals(plain.conviction() - 8, penalised.conviction());
		assertTrue(penalised.notes().stream().anyMatch(n -> n.startsWith("Lesson applied (−8 conviction)")));
	}

	@Test
	void aLessonBoostCannotRaiseConvictionAboveWhatTheConsensusSupports() {
		Result r = DeepVerdictGuard.apply(new Draft(DeepVerdict.WORTH_BUYING, 30, 99), withLessons(bullish(), effect(10, null, null)));

		assertTrue(r.conviction() <= 40 + 60 * Math.min(1.0, Math.abs(r.consensus()) / 0.6) + 1);
	}

	@Test
	void aLessonHoldCapShortensTheHold() {
		Result r = DeepVerdictGuard.apply(new Draft(DeepVerdict.WORTH_BUYING, 90, 75), withLessons(bullish(), effect(0, null, 10)));

		assertEquals(7, r.holdDays());
		assertTrue(r.notes().stream().anyMatch(n -> n.contains("capped at 7 days by a lesson")));
	}

	@Test
	void lessonsNeverUpgradeAWait() {
		Result r = DeepVerdictGuard.apply(new Draft(DeepVerdict.WAIT, null, 40), withLessons(bullish(), effect(10, null, null)));

		assertEquals(DeepVerdict.WAIT, r.verdict());
	}

	// ---- Agent 11's own track record ----

	private static Input withTrack(Input base, TrackRecord tr) {
		return new Input(base.specialists(), base.skepticSeverity(), base.fundamentalsApplicable(), base.fundamentalScore(), base.isEtf(),
				base.lastPrice(), base.earningsInTradingDays(), base.chartAvailable(), null, tr);
	}

	@Test
	void aPoorTrackRecordCapsConvictionAt55() {
		Result r = DeepVerdictGuard.apply(new Draft(DeepVerdict.WORTH_BUYING, 30, 85), withTrack(bullish(), new TrackRecord(30, 0.40, -2.0, 30)));

		assertEquals(DeepVerdict.WORTH_BUYING, r.verdict());
		assertTrue(r.conviction() <= 55, "conviction was " + r.conviction());
		assertTrue(r.notes().stream().anyMatch(n -> n.contains("own past") && n.contains("40%")), r.notes().toString());
	}

	@Test
	void aNegativeMeanExcessAloneAlsoCapsEvenWithADecentHitRate() {
		Result r = DeepVerdictGuard.apply(new Draft(DeepVerdict.WORTH_BUYING, 30, 85), withTrack(bullish(), new TrackRecord(30, 0.60, -3.0, 30)));

		assertTrue(r.conviction() <= 55);
	}

	@Test
	void aGoodOrThinTrackRecordChangesNothing() {
		int base = DeepVerdictGuard.apply(new Draft(DeepVerdict.WORTH_BUYING, 30, 85), bullish()).conviction();

		assertEquals(base, DeepVerdictGuard.apply(new Draft(DeepVerdict.WORTH_BUYING, 30, 85), withTrack(bullish(), new TrackRecord(30, 0.62, 2.0, 30))).conviction());
		assertEquals(base, DeepVerdictGuard.apply(new Draft(DeepVerdict.WORTH_BUYING, 30, 85), withTrack(bullish(), new TrackRecord(5, 0.10, -9.0, 30))).conviction(),
				"five matured verdicts is noise, not a track record");
		assertEquals(base, DeepVerdictGuard.apply(new Draft(DeepVerdict.WORTH_BUYING, 30, 85), withTrack(bullish(), null)).conviction());
	}
}
