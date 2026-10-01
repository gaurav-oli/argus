package com.argus.strategy;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;

/**
 * One ticker's daily bars, ascending by date, with the window math every published price signal is defined in
 * terms of: "the return between months t-12 and t-1", "the maximum daily return over the previous month", "the
 * standard deviation of monthly volume over 36 months".
 *
 * <p>Windows are calendar months resolved against the actual trading calendar (the last bar on or before a date),
 * not 21-day approximations — a month with a holiday in it would otherwise shift every lookback. Pure: no I/O,
 * so each signal can be checked against a hand-built series.
 */
public final class Series {

	/** One daily bar. {@code volume} is in shares; 0 when the source had none. */
	public record Bar(LocalDate date, double close, double high, double low, double volume) {
	}

	/** One calendar month's aggregate: the close of its last session, and the month's total share volume. */
	public record Month(YearMonth month, double close, double volume, int sessions) {
	}

	private final List<Bar> bars;
	private final List<Month> months;

	public Series(List<Bar> ascendingBars) {
		this.bars = List.copyOf(ascendingBars);
		this.months = monthly(this.bars);
	}

	public List<Bar> bars() {
		return bars;
	}

	public List<Month> months() {
		return months;
	}

	public boolean isEmpty() {
		return bars.isEmpty();
	}

	public LocalDate lastDate() {
		return bars.isEmpty() ? null : bars.get(bars.size() - 1).date();
	}

	/** The last bar on or before {@code date} — the price a signal defined "as of" that date would have seen. */
	public OptionalDouble closeOnOrBefore(LocalDate date) {
		for (int i = bars.size() - 1; i >= 0; i--) {
			if (!bars.get(i).date().isAfter(date)) {
				return OptionalDouble.of(bars.get(i).close());
			}
		}
		return OptionalDouble.empty();
	}

	/**
	 * Simple return between two month offsets back from {@code asOf} — the shape almost every momentum and
	 * reversal signal takes. {@code from} is the older offset: {@code ret(asOf, 12, 1)} is "the return between
	 * months t-12 and t-1".
	 */
	public OptionalDouble monthReturn(LocalDate asOf, int fromMonthsBack, int toMonthsBack) {
		OptionalDouble start = closeOnOrBefore(asOf.minusMonths(fromMonthsBack));
		OptionalDouble end = closeOnOrBefore(asOf.minusMonths(toMonthsBack));
		if (start.isEmpty() || end.isEmpty() || start.getAsDouble() <= 0) {
			return OptionalDouble.empty();
		}
		return OptionalDouble.of(end.getAsDouble() / start.getAsDouble() - 1);
	}

	/** Daily simple returns for bars inside {@code (from, to]}. */
	public List<Double> dailyReturns(LocalDate from, LocalDate to) {
		List<Double> out = new ArrayList<>();
		for (int i = 1; i < bars.size(); i++) {
			Bar prev = bars.get(i - 1);
			Bar cur = bars.get(i);
			if (cur.date().isAfter(from) && !cur.date().isAfter(to) && prev.close() > 0) {
				out.add(cur.close() / prev.close() - 1);
			}
		}
		return out;
	}

	/** Bars inside {@code (from, to]} — for the dollar-volume and range signals. */
	public List<Bar> barsBetween(LocalDate from, LocalDate to) {
		List<Bar> out = new ArrayList<>();
		for (Bar b : bars) {
			if (b.date().isAfter(from) && !b.date().isAfter(to)) {
				out.add(b);
			}
		}
		return out;
	}

	/** Highest close in {@code (from, to]} — the 52-week-high signals. */
	public OptionalDouble maxClose(LocalDate from, LocalDate to) {
		double max = Double.NEGATIVE_INFINITY;
		for (Bar b : barsBetween(from, to)) {
			max = Math.max(max, b.close());
		}
		return max == Double.NEGATIVE_INFINITY ? OptionalDouble.empty() : OptionalDouble.of(max);
	}

	/** Monthly returns, oldest first, each tagged with the month it belongs to — the seasonality signals' input. */
	public List<MonthReturn> monthlyReturns(LocalDate asOf, int monthsBack) {
		List<MonthReturn> out = new ArrayList<>();
		YearMonth end = YearMonth.from(asOf);
		for (int i = monthsBack; i >= 1; i--) {
			YearMonth m = end.minusMonths(i);
			OptionalDouble prev = closeOnOrBefore(m.minusMonths(1).atEndOfMonth());
			OptionalDouble cur = closeOnOrBefore(m.atEndOfMonth());
			if (prev.isPresent() && cur.isPresent() && prev.getAsDouble() > 0) {
				out.add(new MonthReturn(m, cur.getAsDouble() / prev.getAsDouble() - 1));
			}
		}
		return out;
	}

	public record MonthReturn(YearMonth month, double ret) {
	}

	/** Monthly aggregates inside the trailing {@code monthsBack} months before {@code asOf}, oldest first. */
	public List<Month> monthsBefore(LocalDate asOf, int monthsBack) {
		YearMonth end = YearMonth.from(asOf);
		YearMonth start = end.minusMonths(monthsBack);
		List<Month> out = new ArrayList<>();
		for (Month m : months) {
			if (m.month().isAfter(start) && !m.month().isAfter(end.minusMonths(1))) {
				out.add(m);
			}
		}
		return out;
	}

	private static List<Month> monthly(List<Bar> bars) {
		Map<YearMonth, double[]> acc = new LinkedHashMap<>(); // [lastClose, volumeSum, sessions]
		for (Bar b : bars) {
			double[] a = acc.computeIfAbsent(YearMonth.from(b.date()), k -> new double[3]);
			a[0] = b.close(); // bars ascend, so the last write is the month's final close
			a[1] += b.volume();
			a[2] += 1;
		}
		List<Month> out = new ArrayList<>();
		acc.forEach((m, a) -> out.add(new Month(m, a[0], a[1], (int) a[2])));
		return out;
	}

	// ---- statistics (sample moments, pure) ----

	public static OptionalDouble mean(List<Double> xs) {
		if (xs.isEmpty()) {
			return OptionalDouble.empty();
		}
		double s = 0;
		for (double x : xs) s += x;
		return OptionalDouble.of(s / xs.size());
	}

	/** Sample standard deviation (n-1). Empty below two observations. */
	public static OptionalDouble stdDev(List<Double> xs) {
		if (xs.size() < 2) {
			return OptionalDouble.empty();
		}
		double m = mean(xs).orElseThrow();
		double ss = 0;
		for (double x : xs) ss += (x - m) * (x - m);
		return OptionalDouble.of(Math.sqrt(ss / (xs.size() - 1)));
	}

	/** Sample skewness (the third standardized moment). Empty below three observations or with no dispersion. */
	public static OptionalDouble skewness(List<Double> xs) {
		if (xs.size() < 3) {
			return OptionalDouble.empty();
		}
		double m = mean(xs).orElseThrow();
		double sd = stdDev(xs).orElse(0);
		if (sd <= 0) {
			return OptionalDouble.empty();
		}
		double s3 = 0;
		for (double x : xs) s3 += Math.pow((x - m) / sd, 3);
		return OptionalDouble.of(s3 / xs.size());
	}

	/** OLS slope of {@code ys} on {@code xs}. Empty below two points or with no variation in x. */
	public static OptionalDouble slope(List<Double> xs, List<Double> ys) {
		if (xs.size() != ys.size() || xs.size() < 2) {
			return OptionalDouble.empty();
		}
		double mx = mean(xs).orElseThrow();
		double my = mean(ys).orElseThrow();
		double sxy = 0;
		double sxx = 0;
		for (int i = 0; i < xs.size(); i++) {
			sxy += (xs.get(i) - mx) * (ys.get(i) - my);
			sxx += (xs.get(i) - mx) * (xs.get(i) - mx);
		}
		return sxx == 0 ? OptionalDouble.empty() : OptionalDouble.of(sxy / sxx);
	}
}
