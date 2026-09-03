package com.argus.portfolio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.argus.technical.PriceCandle;
import com.argus.technical.PriceCandleRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Unit tests for the deterministic health-score engine (Story 3.8, FR-6) — no Spring, no LLM. */
class HealthScoreServiceTest {

	private final PositionRepository positions = mock(PositionRepository.class);
	private final PriceCandleRepository candles = mock(PriceCandleRepository.class);
	private final HealthScoreService service =
			new HealthScoreService(positions, mock(HealthScoreRepository.class), candles);

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

	/** Candles newest-first (matching the real repository contract), one per day starting Jan 1 2026,
	 * built from {@code closes} given oldest→newest. */
	private static List<PriceCandle> descendingCandles(String ticker, double... closes) {
		List<PriceCandle> ascending = new ArrayList<>();
		LocalDate start = LocalDate.of(2026, 1, 1);
		for (int i = 0; i < closes.length; i++) {
			BigDecimal price = BigDecimal.valueOf(closes[i]);
			ascending.add(new PriceCandle(ticker, start.plusDays(i), price, price, price, price, 1000L));
		}
		List<PriceCandle> descending = new ArrayList<>(ascending);
		java.util.Collections.reverse(descending);
		return descending;
	}

	// A 25-day series with real day-to-day variance (not a flat trend), and B = 2×A exactly — a
	// constant multiple of a series has IDENTICAL returns at every step, so Pearson correlation is
	// exactly 1.0 regardless of the series' own shape (hand-verifiable, not just "trust the library").
	private static final double[] SERIES_A = {100, 105, 102, 108, 104, 111, 107, 114, 110, 117, 113, 120, 116, 123,
			119, 126, 122, 129, 125, 132, 128, 135, 131, 138, 134};

	private static double[] scaled(double[] series, double factor) {
		double[] out = new double[series.length];
		for (int i = 0; i < series.length; i++) {
			out[i] = series[i] * factor;
		}
		return out;
	}

	// Hand-verified (via a standalone Pearson computation, not just eyeballed) to correlate with
	// SERIES_A at ~ -0.001 — genuinely uncorrelated, not just "looks different."
	private static final double[] SERIES_UNCORRELATED = {100, 98.77, 99.28, 97.76, 98.16, 98.48, 96.17, 94.47,
			96.53, 96.87, 95.08, 97.45, 95.81, 96.09, 95.46, 96.49, 96.38, 95.85, 95.25, 93.65, 93.79, 93.18,
			93.38, 92.27, 92.84};

	// Hand-verified (standalone Pearson computation) to correlate with SERIES_A at ~ -1.0 — moves
	// opposite to A at (almost) every step, a real hedge.
	private static final double[] SERIES_NEGATIVELY_CORRELATED = {100, 97.0, 98.66, 95.18, 97.3, 93.37, 95.39,
			91.64, 93.57, 90.0, 91.84, 88.43, 90.2, 86.93, 88.63, 85.5, 87.13, 84.13, 85.7, 82.82, 84.32, 81.55,
			83.0, 80.34, 81.74};

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

	// ---- correlation risk ----

	@Test
	void correlatedHoldingsAboveWeightThresholdLoseCorrelationPoints() {
		// A + B are perfectly correlated (B = 2×A) and together 60% of the portfolio — well past
		// both the 0.7 correlation and 15% combined-weight thresholds.
		holdings(pos("AAA", "300", false, false), pos("BBB", "300", false, false),
				pos("CCC", "100", false, false), pos("DDD", "100", false, false), pos("EEE", "200", false, false));
		when(candles.findTop200ByTickerOrderByCandleDateDesc(eq("AAA")))
				.thenReturn(descendingCandles("AAA", SERIES_A));
		when(candles.findTop200ByTickerOrderByCandleDateDesc(eq("BBB")))
				.thenReturn(descendingCandles("BBB", scaled(SERIES_A, 2.0)));

		HealthScoreResult r = service.compute();

		assertTrue(has(r, "correlation_risk"));
		String reason = r.deductions().stream().filter(d -> d.code().equals("correlation_risk"))
				.findFirst().orElseThrow().reason();
		assertTrue(reason.contains("AAA") && reason.contains("BBB"));
	}

	@Test
	void correlatedHoldingsBelowWeightThresholdAreNotFlagged() {
		// Same perfectly-correlated A/B series, but each is only 3% of the portfolio (6% combined) —
		// below the 15% combined-weight floor, so real statistical correlation alone isn't enough.
		holdings(pos("AAA", "30", false, false), pos("BBB", "30", false, false),
				pos("CCC", "300", false, false), pos("DDD", "300", false, false), pos("EEE", "340", false, false));
		when(candles.findTop200ByTickerOrderByCandleDateDesc(eq("AAA")))
				.thenReturn(descendingCandles("AAA", SERIES_A));
		when(candles.findTop200ByTickerOrderByCandleDateDesc(eq("BBB")))
				.thenReturn(descendingCandles("BBB", scaled(SERIES_A, 2.0)));

		HealthScoreResult r = service.compute();

		assertTrue(!has(r, "correlation_risk"), "6% combined weight must not clear the 15% floor");
	}

	@Test
	void uncorrelatedHoldingsAtHighWeightAreNotFlagged() {
		// AAA + CCC are 60% combined (well past the weight floor) but their price series move in a
		// distinctly different rhythm — real weight alone isn't enough without real correlation.
		holdings(pos("AAA", "300", false, false), pos("CCC", "300", false, false),
				pos("DDD", "100", false, false), pos("EEE", "100", false, false), pos("FFF", "200", false, false));
		when(candles.findTop200ByTickerOrderByCandleDateDesc(eq("AAA")))
				.thenReturn(descendingCandles("AAA", SERIES_A));
		when(candles.findTop200ByTickerOrderByCandleDateDesc(eq("CCC")))
				.thenReturn(descendingCandles("CCC", SERIES_UNCORRELATED));

		HealthScoreResult r = service.compute();

		assertTrue(!has(r, "correlation_risk"), "an out-of-phase series must not read as correlated");
	}

	@Test
	void stronglyNegativelyCorrelatedHoldingsAreNeverFlaggedAsRisk() {
		// AAA and GGG move in opposite directions (correlation ~ -1.0, hand-verified via a
		// standalone Pearson computation) — a real hedge, not concentration risk. Regression test
		// for a bug this exact fixture caught during development: an earlier version used
		// Math.abs(correlation), which would have wrongly flagged a strong hedge as risk.
		holdings(pos("AAA", "300", false, false), pos("GGG", "300", false, false),
				pos("DDD", "100", false, false), pos("EEE", "100", false, false), pos("FFF", "200", false, false));
		when(candles.findTop200ByTickerOrderByCandleDateDesc(eq("AAA")))
				.thenReturn(descendingCandles("AAA", SERIES_A));
		when(candles.findTop200ByTickerOrderByCandleDateDesc(eq("GGG")))
				.thenReturn(descendingCandles("GGG", SERIES_NEGATIVELY_CORRELATED));

		HealthScoreResult r = service.compute();

		assertTrue(!has(r, "correlation_risk"), "a real hedge (negative correlation) must never be flagged as risk");
	}

	@Test
	void noCorrelationCheckWithoutEnoughCandleHistoryYet() {
		// candles left unstubbed -> empty per ticker, matching Agent 10 not having ingested this
		// ticker yet (today's actual production state) — must degrade to no deduction, not an error.
		holdings(pos("AAA", "300", false, false), pos("BBB", "300", false, false),
				pos("CCC", "100", false, false), pos("DDD", "100", false, false), pos("EEE", "200", false, false));

		HealthScoreResult r = service.compute();

		assertTrue(!has(r, "correlation_risk"));
	}
}
