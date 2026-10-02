package com.argus.portfolio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.argus.portfolio.CorrelationRisk.Bar;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class CorrelationRiskTest {

	private static final LocalDate D0 = LocalDate.of(2026, 1, 1);
	private static final double[] CHANGES = {1, -0.5, 2, -1, 0.5, 1.5, -0.8, 2.2, -1.5, 0.9, 1.1, -0.6, 1.8, -1.2, 0.7, 1.3, -0.9, 2.0, -1.1, 0.6, 1.4};

	private static List<Bar> series(double start, double[] pctChanges) {
		List<Bar> out = new ArrayList<>();
		double p = start;
		LocalDate d = D0;
		out.add(new Bar(d, p));
		for (double pct : pctChanges) {
			d = d.plusDays(1);
			p *= 1 + pct / 100.0;
			out.add(new Bar(d, p));
		}
		return out;
	}

	private static double[] negate(double[] xs) {
		double[] out = new double[xs.length];
		for (int i = 0; i < xs.length; i++) out[i] = -xs[i];
		return out;
	}

	@Test
	void identicalReturnsAtDifferentPriceLevelsAreScaleInvariantAndPerfectlyCorrelated() {
		// Same % moves every day, different starting price and absolute level — correlation only sees
		// the shape of the returns, not the price, so this must read as 1.0 regardless of scale.
		double c = CorrelationRisk.correlation(series(100, CHANGES), series(50, CHANGES), 20).orElseThrow();

		assertEquals(1.0, c, 1e-9);
	}

	@Test
	void mirroredReturnsArePerfectlyNegativelyCorrelated() {
		double c = CorrelationRisk.correlation(series(100, CHANGES), series(70, negate(CHANGES)), 20).orElseThrow();

		assertEquals(-1.0, c, 1e-9);
	}

	@Test
	void tooFewSharedTradingDaysIsEmptyNotAGuess() {
		List<Bar> short1 = series(100, CHANGES).subList(0, 10);
		List<Bar> short2 = series(50, CHANGES).subList(0, 10);

		assertTrue(CorrelationRisk.correlation(short1, short2, 20).isEmpty());
	}

	@Test
	void onlyTheOverlappingDatesAreUsed() {
		// `a` has 5 chaotic extra days up front that `b` doesn't share; within the dates they do share,
		// the day-over-day moves are identical. If the extra days leaked into the calculation they'd
		// ruin the correlation (they look nothing like CHANGES) — since they're outside the overlap,
		// they must not matter at all.
		double[] wildPrefix = {50, -30, 80, -60, 40};
		List<Bar> a = series(100, concat(wildPrefix, CHANGES)); // D0 .. D0+26, CHANGES-driven from D0+5 on
		List<Bar> b = series(50, CHANGES); // D0 .. D0+21
		List<Bar> bShifted = new ArrayList<>();
		for (Bar bar : b) bShifted.add(new Bar(bar.date().plusDays(5), bar.close())); // -> D0+5 .. D0+26

		double c = CorrelationRisk.correlation(a, bShifted, 10).orElseThrow();

		assertEquals(1.0, c, 1e-9, "the shared CHANGES-driven window is perfectly correlated once the unshared wild days are excluded");
	}

	private static double[] concat(double[] a, double[] b) {
		double[] out = new double[a.length + b.length];
		System.arraycopy(a, 0, out, 0, a.length);
		System.arraycopy(b, 0, out, a.length, b.length);
		return out;
	}
}
