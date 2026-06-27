package com.argus.ops;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** The pure staleness rule behind the freshness monitor (Story 9.7). */
class FreshnessServiceTest {

	private final Instant now = Instant.parse("2026-06-26T12:00:00Z");

	@Test
	void neverUpdatedIsStale() {
		assertTrue(FreshnessService.isStale(null, Duration.ofMinutes(30), now));
	}

	@Test
	void withinThresholdIsFresh() {
		Instant tenMinAgo = now.minus(Duration.ofMinutes(10));
		assertFalse(FreshnessService.isStale(tenMinAgo, Duration.ofMinutes(30), now));
	}

	@Test
	void olderThanThresholdIsStale() {
		Instant fortyMinAgo = now.minus(Duration.ofMinutes(40));
		assertTrue(FreshnessService.isStale(fortyMinAgo, Duration.ofMinutes(30), now));
	}
}
