package com.argus.technical;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Agent 10's normal: the average chart score over the prior 20 sessions, each read as the chart stood that day. */
class ChartNormalScoreTest {

	private static List<PriceCandle> series(int n, double start, double step) {
		List<PriceCandle> out = new ArrayList<>();
		for (int i = 0; i < n; i++) {
			double c = start + step * i;
			BigDecimal bc = BigDecimal.valueOf(c);
			out.add(new PriceCandle("T", LocalDate.of(2025, 1, 1).plusDays(i), bc, BigDecimal.valueOf(c + 1),
					BigDecimal.valueOf(c - 1), bc, 1_000_000L));
		}
		return out;
	}

	@Test
	void aSteadyTrendsNormalMatchesTodaysRead() {
		List<PriceCandle> bars = series(260, 50, 0.3);
		double today = ChartReader.study(bars, List.of()).orElseThrow().score();
		double normal = ChartStudyService.normalScore(bars, List.of()).orElseThrow();
		assertTrue(Math.abs(today - normal) < 0.1); // weeks of the same uptrend: no change from normal, so no vote
	}

	@Test
	void neverLooksAtTheLatestSession() {
		List<PriceCandle> bars = series(260, 50, 0.3);
		List<PriceCandle> withCrash = new ArrayList<>(bars);
		withCrash.set(259, new PriceCandle("T", bars.get(259).getCandleDate(), BigDecimal.valueOf(60), BigDecimal.valueOf(61),
				BigDecimal.valueOf(20), BigDecimal.valueOf(21), 9_000_000L));
		assertEquals(ChartStudyService.normalScore(bars, List.of()), ChartStudyService.normalScore(withCrash, List.of()));
	}

	@Test
	void tooLittleHistoryHasNoNormal() {
		Optional<Double> normal = ChartStudyService.normalScore(series(ChartReader.MIN_BARS + 3, 50, 0.3), List.of());
		assertTrue(normal.isEmpty());
	}
}
