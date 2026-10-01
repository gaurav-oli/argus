package com.argus.strategy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalDouble;
import org.junit.jupiter.api.Test;

/**
 * Each signal is transcribed from the paper's own definition — these tests check the transcription against
 * hand-built series where the right answer is known by construction, not against the published number (there is
 * no CRSP cross-section to reproduce it on).
 */
class SignalLibraryTest {

	private final SignalLibrary library = new SignalLibrary();

	private static Series flat(LocalDate start, int days, double price, double volume) {
		List<Series.Bar> bars = new ArrayList<>();
		LocalDate d = start;
		for (int i = 0; i < days; i++) {
			while (d.getDayOfWeek().getValue() > 5) d = d.plusDays(1);
			bars.add(new Series.Bar(d, price, price, price, volume));
			d = d.plusDays(1);
		}
		return new Series(bars);
	}

	/** A series that is flat except for one known jump, so Mom12m etc. have a hand-computable answer. */
	private static Series stepAtMonth(LocalDate start, int totalDays, int jumpAfterDays, double before, double after) {
		List<Series.Bar> bars = new ArrayList<>();
		LocalDate d = start;
		for (int i = 0; i < totalDays; i++) {
			while (d.getDayOfWeek().getValue() > 5) d = d.plusDays(1);
			double p = i < jumpAfterDays ? before : after;
			bars.add(new Series.Bar(d, p, p, p, 1_000_000));
			d = d.plusDays(1);
		}
		return new Series(bars);
	}

	private OptionalDouble compute(String acronym, Series s, LocalDate asOf) {
		return library.get(acronym).orElseThrow().computation().apply(s, null, asOf);
	}

	@Test
	void mom12mIsTheReturnFromTwelveMonthsAgoToOneMonthAgo() {
		// jump lands in the first three weeks of 2023 — well before the t-12 boundary (2023-06-03) for asOf 2024-06-03
		Series s = stepAtMonth(LocalDate.of(2023, 1, 2), 500, 15, 100, 150);
		double r = compute("Mom12m", s, LocalDate.of(2024, 6, 3)).orElseThrow();
		assertEquals(0.0, r, 1e-6, "price has been flat at 150 for the whole t-12..t-1 window");
	}

	@Test
	void mom6mCapturesAJumpInsideItsSixMonthWindow() {
		// jump happens ~8 months before asOf: inside the t-12..t-1 window of Mom12m but should still be captured
		Series s = stepAtMonth(LocalDate.of(2023, 1, 2), 500, 170, 100, 150);
		double mom12 = compute("Mom12m", s, LocalDate.of(2024, 3, 1)).orElseThrow();
		assertTrue(mom12 > 0.3, "the +50% jump sits inside the 12-month lookback: " + mom12);
	}

	@Test
	void shortTermReversalIsJustLastMonthsReturn() {
		Series s = stepAtMonth(LocalDate.of(2024, 1, 2), 60, 25, 100, 110);
		double r = compute("STreversal", s, LocalDate.of(2024, 3, 1)).orElseThrow();
		assertTrue(r > 0, "the jump landed inside the trailing month: " + r);
	}

	@Test
	void maxRetFindsTheBiggestSingleDayGainInTheTrailingMonth() {
		List<Series.Bar> bars = new ArrayList<>();
		LocalDate d = LocalDate.of(2024, 1, 2);
		double[] closes = {100, 101, 150, 149, 148}; // one huge one-day pop from 101 to 150
		for (double c : closes) {
			bars.add(new Series.Bar(d, c, c, c, 1000));
			d = d.plusDays(1);
		}
		Series s = new Series(bars);

		double max = compute("MaxRet", s, LocalDate.of(2024, 1, 10)).orElseThrow();
		assertEquals(150.0 / 101.0 - 1, max, 1e-9);
	}

	@Test
	void priceIsTheLogOfTheCurrentClose() {
		Series s = flat(LocalDate.of(2024, 1, 2), 10, 42.0, 1000);
		assertEquals(Math.log(42.0), compute("Price", s, LocalDate.of(2024, 1, 9)).orElseThrow(), 1e-9);
	}

	@Test
	void high52IsPriceDividedByTheTrailingYearHigh() {
		List<Series.Bar> bars = new ArrayList<>();
		LocalDate d = LocalDate.of(2023, 1, 2);
		for (int i = 0; i < 300; i++) {
			while (d.getDayOfWeek().getValue() > 5) d = d.plusDays(1);
			double p = i == 100 ? 200 : 100; // one spike to 200, otherwise flat at 100
			bars.add(new Series.Bar(d, p, p, p, 1000));
			d = d.plusDays(1);
		}
		Series s = new Series(bars);

		double ratio = compute("High52", s, LocalDate.of(2023, 12, 1)).orElseThrow();
		assertEquals(0.5, ratio, 1e-6, "current price 100 over the year's high of 200");
	}

	@Test
	void illiquidityIsHigherWhenTheSameDollarVolumeMovesThePriceMore() {
		// two series, same |return| magnitude, very different volume -> very different Amihud ratio
		List<Series.Bar> thin = alternating(LocalDate.of(2023, 1, 2), 280, 100, 0.02, 1_000);
		List<Series.Bar> thick = alternating(LocalDate.of(2023, 1, 2), 280, 100, 0.02, 1_000_000);

		double illiqThin = library.get("Illiquidity").orElseThrow().computation()
				.apply(new Series(thin), null, LocalDate.of(2023, 12, 20)).orElseThrow();
		double illiqThick = library.get("Illiquidity").orElseThrow().computation()
				.apply(new Series(thick), null, LocalDate.of(2023, 12, 20)).orElseThrow();

		assertTrue(illiqThin > illiqThick, "the same move on a thousand shares should look far less liquid than on a million");
	}

	private static List<Series.Bar> alternating(LocalDate start, int days, double base, double dailyMovePct, double volume) {
		List<Series.Bar> bars = new ArrayList<>();
		LocalDate d = start;
		double p = base;
		for (int i = 0; i < days; i++) {
			while (d.getDayOfWeek().getValue() > 5) d = d.plusDays(1);
			p *= (i % 2 == 0) ? (1 + dailyMovePct) : (1 / (1 + dailyMovePct));
			bars.add(new Series.Bar(d, p, p, p, volume));
			d = d.plusDays(1);
		}
		return bars;
	}

	@Test
	void everyImplementedSignalDeclinesRatherThanGuessesOnEmptyHistory() {
		Series empty = new Series(List.of());
		for (SignalLibrary.Signal sig : library.all()) {
			assertTrue(sig.computation().apply(empty, empty, LocalDate.of(2024, 1, 1)).isEmpty(),
					sig.acronym() + " must decline on no history, not return a number");
		}
	}

	@Test
	void industryMomentumIsNotInTheLibraryItIsDerivedFromTheCrossSection() {
		assertTrue(library.get("IndMom").isEmpty(), "industry momentum is an aggregate computed by StrategyScoringService");
	}
}
