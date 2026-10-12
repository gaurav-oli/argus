package com.argus.recommendation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.argus.recommendation.PositionRules.Action;
import com.argus.recommendation.PositionRules.Decision;
import com.argus.recommendation.PositionRules.Market;
import com.argus.recommendation.PositionRules.Position;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** The paper investor's position-management rules. ATR is 2% throughout unless stated. */
class PositionRulesTest {

	private static final Instant ENTRY = Instant.parse("2026-10-01T15:00:00Z");
	private static final Instant LATER = ENTRY.plus(Duration.ofDays(3));

	private static Position longAt100(Double stop, Double hw, Double target, boolean scaledOut, boolean trailed) {
		return new Position(true, 100, stop, hw, target, scaledOut, trailed, ENTRY);
	}

	private static Market at(double price) {
		return new Market(price, 2.0, null, null, false);
	}

	@Test
	void theHardStopExitsAtTheLivePriceEvenInsideTheMinimumHold() {
		Decision d = PositionRules.evaluate(longAt100(95.0, null, null, false, false), at(94.0), ENTRY.plus(Duration.ofHours(1)));
		assertEquals(Action.EXIT, d.action());
		assertEquals("STOP", d.exitReason());
	}

	@Test
	void aGapThroughTheStopIsStillAStopOut() {
		Decision d = PositionRules.evaluate(longAt100(95.0, null, null, false, false), at(88.0), LATER);
		assertEquals(Action.EXIT, d.action(), "the caller fills at 88, the live price — never at the 95 stop");
	}

	@Test
	void aStopThatWasTightenedExitsAsATrailingStop() {
		Decision d = PositionRules.evaluate(longAt100(101.0, 106.0, null, false, true), at(100.5), LATER);
		assertEquals("TRAILING_STOP", d.exitReason());
	}

	@Test
	void nothingButTheHardStopActsInsideTheFirstDay() {
		Decision d = PositionRules.evaluate(longAt100(95.0, null, 104.0, false, false), at(105.0), ENTRY.plus(Duration.ofHours(5)));
		assertEquals(Action.HOLD, d.action());
		assertEquals(95.0, d.stop(), "no take-profit and no ratchet before 24h");
	}

	@Test
	void oneAtrInTheMoneyMovesTheStopToBreakevenThenItTrails() {
		Decision breakeven = PositionRules.evaluate(longAt100(95.0, null, null, false, false), at(102.0), LATER);
		assertEquals(100.0, breakeven.stop(), 1e-9, "+2% = 1 ATR: stop to entry (2.5 ATR trail from 102 is lower)");

		Decision trailing = PositionRules.evaluate(longAt100(100.0, 102.0, null, false, true), at(110.0), LATER);
		assertEquals(110 * (1 - 0.02), trailing.stop(), 1e-9, "+10% = 5 ATR in the money: 1 ATR behind the new high");
		assertEquals(110.0, trailing.highWater());
	}

	@Test
	void theStopNeverLoosens() {
		Decision d = PositionRules.evaluate(longAt100(102.5, 104.0, null, false, true), at(103.0), LATER);
		assertEquals(102.5, d.stop(), 1e-9, "price fell back from the high: the stop stays where it was");
	}

	@Test
	void aNearbySupportTightensTheTrailButNotIntoTheNoise() {
		Market withSupport = new Market(103.5, 2.0, 101.4, null, false);
		Decision d = PositionRules.evaluate(longAt100(100.0, 103.5, null, false, true), withSupport, LATER);
		assertEquals(101.4 * (1 - 0.01), d.stop(), 1e-9, "just beyond support at 101.4, which is ≥1 ATR (2.07) below price");

		Market tooClose = new Market(103.5, 2.0, 101.5, null, false);
		Decision d2 = PositionRules.evaluate(longAt100(100.0, 103.5, null, false, true), tooClose, LATER);
		assertEquals(100.0, d2.stop(), 1e-9, "support inside 1 ATR is ignored; breakeven beats the 2.5-ATR trail");
	}

	@Test
	void theTrailNarrowsAsTheGainGrows() {
		// Best price 1.5, 2.5 and 4 ATR in the money (ATR 2%): trails 2.5, 1.5 and 1 ATR behind it.
		assertEquals(100.0, PositionRules.evaluate(longAt100(100.0, 103.0, null, false, true), at(103.0), LATER).stop(), 1e-9,
				"103 × (1 − 5%) = 97.85 is below breakeven");
		assertEquals(105 * (1 - 0.03), PositionRules.evaluate(longAt100(100.0, 105.0, null, false, true), at(105.0), LATER).stop(), 1e-9);
		assertEquals(108 * (1 - 0.02), PositionRules.evaluate(longAt100(100.0, 108.0, null, false, true), at(108.0), LATER).stop(), 1e-9);

		// The width follows the best price, not today's: a fade from 108 to 106.5 keeps the 1-ATR trail from 108.
		assertEquals(108 * (1 - 0.02), PositionRules.evaluate(longAt100(100.0, 108.0, null, false, true), at(106.5), LATER).stop(), 1e-9);
		// …but never closer than 0.25 ATR under today's price: a deeper fade to 106 caps it there.
		assertEquals(106 * (1 - 0.005), PositionRules.evaluate(longAt100(100.0, 108.0, null, false, true), at(106.0), LATER).stop(), 1e-9);

		assertEquals(2.5, PositionRules.trailAtrs(1.99));
		assertEquals(1.5, PositionRules.trailAtrs(2.0));
		assertEquals(1.0, PositionRules.trailAtrs(3.0));
	}

	@Test
	void theTargetTakesHalfOffOnceAndTheRestTrailsFromBreakeven() {
		Decision d = PositionRules.evaluate(longAt100(95.0, null, 104.0, false, false), at(104.5), LATER);
		assertEquals(Action.TAKE_HALF, d.action());
		assertEquals(100.0, d.stop(), 1e-9);

		Decision again = PositionRules.evaluate(longAt100(100.0, 104.5, 104.0, true, true), at(105.0), LATER);
		assertEquals(Action.HOLD, again.action(), "already scaled out");
	}

	@Test
	void earningsTightenTheStopToOneAtr() {
		Market earnings = new Market(100.0, 2.0, null, null, true);
		Decision d = PositionRules.evaluate(longAt100(90.0, null, null, false, false), earnings, LATER);
		assertEquals(98.0, d.stop(), 1e-9);
	}

	@Test
	void shortsMirrorEverything() {
		Position shortAt100 = new Position(false, 100, 105.0, null, 95.0, false, false, ENTRY);
		assertEquals("STOP", PositionRules.evaluate(shortAt100, at(106.0), LATER).exitReason());
		assertEquals(Action.TAKE_HALF, PositionRules.evaluate(shortAt100, at(94.0), LATER).action());

		Position shortRunning = new Position(false, 100, 105.0, 98.0, null, false, false, ENTRY);
		Decision d = PositionRules.evaluate(shortRunning, at(90.0), LATER);
		assertEquals(90 * 1.02, d.stop(), 1e-9, "−10% = 5 ATR in the money: 1 ATR above the new low");
		assertEquals(90.0, d.highWater());
	}

	@Test
	void anOppositeCallExitsAWatchCallTightensAnAgreeingCallDoesNothing() {
		Position p = longAt100(90.0, null, null, false, false);
		assertEquals("THESIS_DECAY", PositionRules.onNewCall(p, true, false, 101, 2.0, LATER).exitReason());
		assertEquals(101 * 0.98, PositionRules.onNewCall(p, false, true, 101, 2.0, LATER).stop(), 1e-9);
		assertEquals(90.0, PositionRules.onNewCall(p, false, false, 101, 2.0, LATER).stop());
		assertEquals(Action.HOLD, PositionRules.onNewCall(p, true, false, 101, 2.0, ENTRY.plus(Duration.ofHours(2))).action(),
				"a reversal inside the first day doesn't churn the trade");
	}

	@Test
	void aMissingAtrFallsBackToATypicalRangeAndAMissingStopIsSetNotLeftOpen() {
		Decision d = PositionRules.evaluate(longAt100(null, null, null, false, false), new Market(104.0, null, null, null, false), LATER);
		assertTrue(d.stop() != null && d.stop() >= 100.0, "3% default ATR: +4% is in the money, stop to at least breakeven");
		assertNull(PositionRules.evaluate(longAt100(null, null, null, false, false), at(100.5), LATER).stop(),
				"not yet in the money: the manager's backfill sets the first stop, not the ratchet");
	}
}
