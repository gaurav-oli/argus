package com.argus.intelligence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Scoring math, clamping, tier/blocked consistency, and the block-transition flag (Story 4.3). */
class SourceCredibilityTest {

	@Test
	void unknownStartsAt35Bronze() {
		SourceCredibility c = SourceCredibility.unknown("acme.news");
		assertEquals(35, c.getScore());
		assertEquals(CredibilityTier.BRONZE, c.getTier());
		assertFalse(c.isBlocked());
	}

	@Test
	void correctAddsTwoIncorrectSubtractsThree() {
		SourceCredibility c = SourceCredibility.unknown("s");
		c.recordOutcome(true);
		assertEquals(37, c.getScore());
		c.recordOutcome(false);
		assertEquals(34, c.getScore());
		assertEquals(1, c.getCorrectCount());
		assertEquals(1, c.getIncorrectCount());
	}

	@Test
	void scoreClampsAtZero() {
		SourceCredibility c = SourceCredibility.unknown("s");
		for (int i = 0; i < 50; i++) {
			c.recordOutcome(false);
		}
		assertEquals(0, c.getScore());
		assertEquals(CredibilityTier.BLOCKED, c.getTier());
		assertTrue(c.isBlocked());
	}

	@Test
	void scoreClampsAtHundred() {
		SourceCredibility c = SourceCredibility.unknown("s");
		for (int i = 0; i < 100; i++) {
			c.recordOutcome(true);
		}
		assertEquals(100, c.getScore());
		assertEquals(CredibilityTier.PLATINUM, c.getTier());
	}

	@Test
	void reportsBlockTransitionExactlyOnce() {
		SourceCredibility c = SourceCredibility.unknown("s"); // 35
		boolean transitioned = false;
		int transitions = 0;
		// 35 → below 10 requires nine −3 steps (35,32,...,11,8).
		for (int i = 0; i < 9; i++) {
			transitioned = c.recordOutcome(false);
			if (transitioned) {
				transitions++;
			}
		}
		assertTrue(c.isBlocked());
		assertEquals(1, transitions, "block transition should be signalled once");

		// Already blocked: further incorrect outcomes must not re-signal.
		assertFalse(c.recordOutcome(false));
	}

	@Test
	void recoveryAboveThresholdUnblocks() {
		SourceCredibility c = SourceCredibility.unknown("s");
		for (int i = 0; i < 9; i++) {
			c.recordOutcome(false); // → 8, blocked
		}
		assertTrue(c.isBlocked());
		c.recordOutcome(true); // 10
		assertFalse(c.isBlocked());
		assertEquals(CredibilityTier.FLAGGED, c.getTier());
	}
}
