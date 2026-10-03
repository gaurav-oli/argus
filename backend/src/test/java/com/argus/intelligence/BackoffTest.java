package com.argus.intelligence;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class BackoffTest {

	private static final Duration INITIAL = Duration.ofMinutes(10);
	private static final Duration MAX = Duration.ofHours(2);

	@Test
	void noFailuresMeansNoBackoff() {
		assertEquals(Duration.ZERO, Backoff.duration(0, INITIAL, MAX));
		assertEquals(Duration.ZERO, Backoff.duration(-3, INITIAL, MAX));
	}

	@Test
	void theFirstFailureWaitsExactlyTheInitialDuration() {
		assertEquals(INITIAL, Backoff.duration(1, INITIAL, MAX));
	}

	@Test
	void eachConsecutiveFailureDoublesTheWait() {
		assertEquals(Duration.ofMinutes(20), Backoff.duration(2, INITIAL, MAX));
		assertEquals(Duration.ofMinutes(40), Backoff.duration(3, INITIAL, MAX));
		assertEquals(Duration.ofMinutes(80), Backoff.duration(4, INITIAL, MAX));
	}

	@Test
	void itNeverExceedsTheCeiling() {
		assertEquals(MAX, Backoff.duration(5, INITIAL, MAX)); // 160min would exceed the 120min cap
		assertEquals(MAX, Backoff.duration(50, INITIAL, MAX));
		assertEquals(MAX, Backoff.duration(Integer.MAX_VALUE, INITIAL, MAX), "a huge failure count must not overflow or wrap");
	}
}
