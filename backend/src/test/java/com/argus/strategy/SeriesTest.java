package com.argus.strategy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Window math the published signals are written in terms of — calendar months against a real trading calendar. */
class SeriesTest {

	/** Daily bars every weekday from {@code start} for {@code days}, compounding at {@code dailyPct}. */
	private static Series series(LocalDate start, int days, double dailyPct, double startPrice) {
		List<Series.Bar> bars = new ArrayList<>();
		double p = startPrice;
		LocalDate d = start;
		for (int i = 0; i < days; i++) {
			while (d.getDayOfWeek().getValue() > 5) {
				d = d.plusDays(1);
			}
			bars.add(new Series.Bar(d, p, p * 1.01, p * 0.99, 1_000_000));
			p *= 1 + dailyPct;
			d = d.plusDays(1);
		}
		return new Series(bars);
	}

	@Test
	void monthReturnMeasuresBetweenTwoMonthOffsets() {
		Series s = series(LocalDate.of(2024, 1, 1), 400, 0.001, 100);
		LocalDate asOf = LocalDate.of(2025, 6, 2);

		double r = s.monthReturn(asOf, 12, 1).orElseThrow();

		// 11 months of compounding at ~0.1%/weekday ≈ 230 sessions ≈ +26%
		assertTrue(r > 0.15 && r < 0.40, "got " + r);
	}

	@Test
	void monthReturnIsEmptyWhenHistoryDoesNotReachBack() {
		Series s = series(LocalDate.of(2025, 1, 1), 30, 0.001, 100);

		assertTrue(s.monthReturn(LocalDate.of(2025, 2, 10), 36, 13).isEmpty());
	}

	@Test
	void closeOnOrBeforeFallsBackToTheLastSessionNotAnExactDate() {
		Series s = series(LocalDate.of(2025, 1, 1), 40, 0.0, 50);

		// a Sunday: resolves to Friday's close rather than giving up
		assertEquals(50.0, s.closeOnOrBefore(LocalDate.of(2025, 1, 19)).orElseThrow(), 1e-9);
	}

	@Test
	void monthlyAggregatesSumVolumeAndKeepTheFinalClose() {
		Series s = series(LocalDate.of(2025, 1, 1), 45, 0.0, 10);
		Series.Month jan = s.months().get(0);

		assertEquals(2025, jan.month().getYear());
		assertEquals(1, jan.month().getMonthValue());
		assertEquals(10.0, jan.close(), 1e-9);
		assertEquals(jan.sessions() * 1_000_000.0, jan.volume(), 1e-6);
	}

	@Test
	void maxCloseFindsTheWindowHigh() {
		List<Series.Bar> bars = new ArrayList<>();
		LocalDate d = LocalDate.of(2025, 1, 6);
		double[] closes = {10, 20, 15, 12};
		for (double c : closes) {
			bars.add(new Series.Bar(d, c, c, c, 1000));
			d = d.plusDays(1);
		}
		Series s = new Series(bars);

		assertEquals(20.0, s.maxClose(LocalDate.of(2025, 1, 1), LocalDate.of(2025, 1, 10)).orElseThrow(), 1e-9);
	}

	@Test
	void statisticsMatchHandComputedValues() {
		List<Double> xs = List.of(1.0, 2.0, 3.0, 4.0);

		assertEquals(2.5, Series.mean(xs).orElseThrow(), 1e-9);
		assertEquals(Math.sqrt(5.0 / 3.0), Series.stdDev(xs).orElseThrow(), 1e-9);
		assertEquals(0.0, Series.skewness(xs).orElseThrow(), 1e-9, "a symmetric sample has no skew");
		assertEquals(1.0, Series.slope(xs, xs).orElseThrow(), 1e-9);
	}

	@Test
	void statisticsDeclineRatherThanGuessOnThinInput() {
		assertTrue(Series.stdDev(List.of(1.0)).isEmpty());
		assertTrue(Series.skewness(List.of(1.0, 2.0)).isEmpty());
		assertTrue(Series.skewness(List.of(2.0, 2.0, 2.0)).isEmpty(), "no dispersion = no skew");
		assertTrue(Series.slope(List.of(1.0, 1.0), List.of(2.0, 3.0)).isEmpty(), "no variation in x");
	}
}
