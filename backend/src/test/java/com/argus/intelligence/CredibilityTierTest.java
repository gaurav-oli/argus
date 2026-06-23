package com.argus.intelligence;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** Score → tier band mapping for FR-9 (Story 4.3). */
class CredibilityTierTest {

	@Test
	void mapsEachBandIncludingBoundaries() {
		assertEquals(CredibilityTier.PLATINUM, CredibilityTier.forScore(100));
		assertEquals(CredibilityTier.PLATINUM, CredibilityTier.forScore(90));
		assertEquals(CredibilityTier.GOLD, CredibilityTier.forScore(89));
		assertEquals(CredibilityTier.GOLD, CredibilityTier.forScore(75));
		assertEquals(CredibilityTier.SILVER, CredibilityTier.forScore(74));
		assertEquals(CredibilityTier.SILVER, CredibilityTier.forScore(50));
		assertEquals(CredibilityTier.BRONZE, CredibilityTier.forScore(49));
		assertEquals(CredibilityTier.BRONZE, CredibilityTier.forScore(35)); // unknown baseline
		assertEquals(CredibilityTier.BRONZE, CredibilityTier.forScore(25));
		assertEquals(CredibilityTier.FLAGGED, CredibilityTier.forScore(24));
		assertEquals(CredibilityTier.FLAGGED, CredibilityTier.forScore(10));
		assertEquals(CredibilityTier.BLOCKED, CredibilityTier.forScore(9));
		assertEquals(CredibilityTier.BLOCKED, CredibilityTier.forScore(0));
	}
}
