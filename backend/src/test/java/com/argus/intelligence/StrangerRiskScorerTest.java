package com.argus.intelligence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.argus.intelligence.MarketDataPort.MarketStats;
import java.math.BigDecimal;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Auditable pump-and-dump risk scoring (Story 4.4) — monotonic, bounded, no LLM. */
class StrangerRiskScorerTest {

	private final StrangerRiskScorer scorer = new StrangerRiskScorer();

	@Test
	void calmDiverseHighCredibilityScoresLow() {
		int s = scorer.score(3, 3, 90.0, Optional.empty());
		assertTrue(s < 25, "expected low risk, got " + s);
	}

	@Test
	void heavyLowCredCoordinatedScoresHigh() {
		int s = scorer.score(8, 1, 10.0, Optional.empty());
		assertTrue(s >= 65, "expected high risk, got " + s);
	}

	@Test
	void moreCoverageRaisesScore() {
		assertTrue(scorer.score(9, 3, 50.0, Optional.empty())
				> scorer.score(3, 3, 50.0, Optional.empty()));
	}

	@Test
	void lowerCredibilityRaisesScore() {
		assertTrue(scorer.score(5, 5, 20.0, Optional.empty())
				> scorer.score(5, 5, 80.0, Optional.empty()));
	}

	@Test
	void coordinatedPostingRaisesScoreOverDiverse() {
		assertTrue(scorer.score(6, 1, 50.0, Optional.empty())
				> scorer.score(6, 6, 50.0, Optional.empty()));
	}

	@Test
	void microCapVolumeSpikeRaisesScore() {
		MarketStats pumpish = new MarketStats(BigDecimal.valueOf(100_000_000L), 5_000_000, 1_000_000);
		assertTrue(scorer.score(5, 2, 50.0, Optional.of(pumpish))
				> scorer.score(5, 2, 50.0, Optional.empty()));
	}

	@Test
	void withoutMarketDataScoreIsCappedBelow100() {
		int maxNewsOnly = scorer.score(1000, 1, 0.0, Optional.empty());
		assertEquals(85, maxNewsOnly, "news+credibility+coordination alone cap at 85");
	}

	@Test
	void scoreAlwaysWithinBounds() {
		int s = scorer.score(0, 0, 0.0, Optional.empty());
		assertTrue(s >= 0 && s <= 100);
	}
}
