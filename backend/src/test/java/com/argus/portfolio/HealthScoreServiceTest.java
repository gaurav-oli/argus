package com.argus.portfolio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Unit tests for the deterministic health-score engine (Story 3.8, FR-6) — no Spring, no LLM. */
class HealthScoreServiceTest {

	private final PositionRepository positions = mock(PositionRepository.class);
	private final HealthScoreService service =
			new HealthScoreService(positions, mock(HealthScoreRepository.class));

	private static Position pos(String ticker, String cadAcb, boolean needsReview, boolean fxEstimated) {
		Position p = new Position(ticker, null, BigDecimal.ONE, BigDecimal.ONE, "USD", null, needsReview, "manual");
		p.updateAcbCaches(BigDecimal.ONE, BigDecimal.ONE, "USD", new BigDecimal(cadAcb), fxEstimated);
		return p;
	}

	private void holdings(Position... ps) {
		when(positions.findAllByOrderByTickerAsc()).thenReturn(List.of(ps));
	}

	private boolean has(HealthScoreResult r, String code) {
		return r.deductions().stream().anyMatch(d -> d.code().equals(code));
	}

	@Test
	void emptyPortfolioScores100() {
		when(positions.findAllByOrderByTickerAsc()).thenReturn(List.of());
		assertEquals(100, service.compute().score());
	}

	@Test
	void wellDiversifiedCleanPortfolioScores100() {
		// 5 equal-weight holdings (20% each), nothing flagged → no deductions.
		holdings(pos("A", "100", false, false), pos("B", "100", false, false), pos("C", "100", false, false),
				pos("D", "100", false, false), pos("E", "100", false, false));
		HealthScoreResult r = service.compute();
		assertEquals(100, r.score());
		assertTrue(r.deductions().isEmpty());
	}

	@Test
	void concentratedPortfolioLosesConcentrationAndDiversificationPoints() {
		holdings(pos("AAA", "900", false, false), pos("BBB", "100", false, false));
		HealthScoreResult r = service.compute();
		assertTrue(r.score() < 100);
		assertTrue(has(r, "concentration_single"));
		assertTrue(has(r, "diversification")); // only 2 holdings
		assertTrue(r.score() >= 0 && r.score() <= 100);
	}

	@Test
	void flaggedHoldingsLoseDataQualityPoints() {
		holdings(pos("A", "100", true, false), pos("B", "100", false, true), pos("C", "100", false, false),
				pos("D", "100", false, false), pos("E", "100", false, false));
		HealthScoreResult r = service.compute();
		assertTrue(has(r, "data_quality"));
		assertEquals(96, r.score()); // 2 flagged × 2 = 4 off; no concentration/diversification hit
	}
}
