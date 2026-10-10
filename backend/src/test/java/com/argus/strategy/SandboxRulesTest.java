package com.argus.strategy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.argus.strategy.SandboxRules.State;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class SandboxRulesTest {

	private static final BigDecimal UP = new BigDecimal("0.8");
	private static final BigDecimal DOWN = new BigDecimal("-0.3");

	@Test
	void staysInShadowUntilThereIsASample() {
		assertEquals(State.SHADOW, SandboxRules.next(State.SHADOW, 0, 0, null).state());
		assertEquals(State.SHADOW, SandboxRules.next(State.SHADOW, 10, 9, UP).state(), "great so far, but only 10");
	}

	@Test
	void becomesACandidateHalfWayWhenAboveTheBar() {
		assertEquals(State.CANDIDATE, SandboxRules.next(State.SHADOW, 16, 10, UP).state());
		assertEquals(State.SHADOW, SandboxRules.next(State.CANDIDATE, 20, 9, UP).state(), "fell below: back to shadow");
	}

	@Test
	void promotesOnlyWithEnoughCallsAHitRateAndAPositiveExcess() {
		SandboxRules.Decision d = SandboxRules.next(State.CANDIDATE, 30, 17, UP);
		assertEquals(State.PROMOTED, d.state());
		assertTrue(d.reason().contains("17 of 30"), d.reason());
		assertEquals(State.KILLED, SandboxRules.next(State.CANDIDATE, 30, 17, DOWN).state(), "hits without excess");
	}

	@Test
	void killsACoinFlipAndOneThatNeverClearsTheBar() {
		assertEquals(State.KILLED, SandboxRules.next(State.SHADOW, 30, 14, UP).state());
		assertEquals(State.SHADOW, SandboxRules.next(State.SHADOW, 40, 21, UP).state(), "53%: keep watching");
		assertEquals(State.KILLED, SandboxRules.next(State.SHADOW, 60, 31, UP).state(), "52% at 60 calls");
	}

	@Test
	void promotedAndKilledAreFinal() {
		assertEquals(State.PROMOTED, SandboxRules.next(State.PROMOTED, 60, 1, DOWN).state());
		assertNull(SandboxRules.next(State.KILLED, 60, 60, UP).reason());
	}

	@Test
	void excessIsDirectionAdjustedAgainstSpy() {
		assertEquals(5.0, SandboxRules.excessPct(1, 100, 110, 500, 525), 1e-9);
		assertEquals(-5.0, SandboxRules.excessPct(-1, 100, 110, 500, 525), 1e-9);
	}
}
