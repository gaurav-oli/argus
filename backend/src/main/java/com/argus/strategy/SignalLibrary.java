package com.argus.strategy;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import org.springframework.stereotype.Component;

/**
 * Argus's implementations of the published price/volume signals — the subset of the corpus it can actually
 * compute from stored daily bars plus the benchmark. Each is transcribed from the paper's own definition as
 * recorded in {@link StrategyDocImporter}'s corpus, and is <b>pure arithmetic</b>: no LLM is involved in
 * producing a number, exactly as the README requires. Where Argus's data forces a departure from the original,
 * the deviation is named in {@link Signal#deviation()} rather than quietly papered over.
 *
 * <p>Of the corpus's ~212 real predictors, 196 need point-in-time accounting or analyst data Argus does not
 * have (Compustat/IBES/13F/options). What is left is price and volume — and that is what lives here.
 *
 * <p>{@code IndMom} is deliberately absent: industry momentum is a cross-sectional aggregate over a sector's
 * members, so it is derived by {@link CrossSectionalRanker} from every member's Mom6m, not computed per ticker.
 */
@Component
public class SignalLibrary {

	/**
	 * One implemented signal.
	 *
	 * @param acronym   the corpus acronym it implements, so the paper and its published t-stat stay attached
	 * @param minBars   daily bars of history needed before the value means anything
	 * @param deviation how this implementation departs from the paper, or null when it is faithful
	 */
	public record Signal(String acronym, String label, int minBars, String deviation, Computation computation) {
	}

	/** The computation itself: a ticker's series, the benchmark's series, and the date it is evaluated at. */
	public interface Computation {
		OptionalDouble apply(Series series, Series benchmark, LocalDate asOf);
	}

	private final Map<String, Signal> byAcronym = new LinkedHashMap<>();

	public SignalLibrary() {
		// ---- momentum and reversal (Jegadeesh & Titman and successors) ----
		add("Mom12m", "Momentum, 12-month", 260, null,
				(s, b, d) -> s.monthReturn(d, 12, 1));
		add("Mom6m", "Momentum, 6-month", 140, null,
				(s, b, d) -> s.monthReturn(d, 6, 1));
		add("IntMom", "Intermediate momentum (t-12 to t-6)", 260, null,
				(s, b, d) -> s.monthReturn(d, 12, 6));
		add("STreversal", "Short-term reversal (prior month)", 25, null,
				(s, b, d) -> s.monthReturn(d, 1, 0));
		add("LRreversal", "Long-run reversal (t-36 to t-13)", 760, null,
				(s, b, d) -> s.monthReturn(d, 36, 13));

		// ---- seasonality (Heston & Sadka 2008) ----
		add("MomSeason", "Return seasonality, same month in years 2-5", 1050, null, (s, b, d) -> {
			List<Double> same = new ArrayList<>();
			for (Series.MonthReturn mr : s.monthlyReturns(d, 60)) {
				int yearsBack = d.getYear() - mr.month().getYear();
				if (mr.month().getMonthValue() == d.getMonthValue() && yearsBack >= 2 && yearsBack <= 5) {
					same.add(mr.ret());
				}
			}
			return same.size() < 2 ? OptionalDouble.empty() : Series.mean(same);
		});
		add("Mom12mOffSeason", "Momentum excluding the same calendar month", 260, null, (s, b, d) -> {
			List<Double> other = new ArrayList<>();
			for (Series.MonthReturn mr : s.monthlyReturns(d, 12)) {
				if (mr.month().getMonthValue() != d.getMonthValue()) {
					other.add(mr.ret());
				}
			}
			return other.size() < 6 ? OptionalDouble.empty() : Series.mean(other);
		});

		// ---- lottery-like payoffs and higher moments ----
		add("MaxRet", "Maximum daily return last month", 25, null, (s, b, d) -> {
			List<Double> rets = s.dailyReturns(d.minusMonths(1), d);
			return rets.isEmpty() ? OptionalDouble.empty() : OptionalDouble.of(rets.stream().mapToDouble(Double::doubleValue).max().orElseThrow());
		});
		add("ReturnSkew", "Skewness of daily returns last month", 25, null,
				(s, b, d) -> Series.skewness(s.dailyReturns(d.minusMonths(1), d)));
		add("CoskewACX", "Coskewness with the market", 260,
				"Measured over 12 months of daily data (the paper leaves the window to the implementation) and against "
						+ "SPY rather than the CRSP value-weighted index.",
				SignalLibrary::coskewness);

		// ---- price level and the 52-week high ----
		add("High52", "Price relative to the 52-week high", 260, null, (s, b, d) -> {
			OptionalDouble price = s.closeOnOrBefore(d);
			OptionalDouble high = s.maxClose(d.minusMonths(12), d);
			return price.isEmpty() || high.isEmpty() || high.getAsDouble() <= 0
					? OptionalDouble.empty() : OptionalDouble.of(price.getAsDouble() / high.getAsDouble());
		});
		add("Price", "Log price (the low-price effect)", 25, null, (s, b, d) -> {
			OptionalDouble price = s.closeOnOrBefore(d);
			return price.isEmpty() || price.getAsDouble() <= 0 ? OptionalDouble.empty() : OptionalDouble.of(Math.log(price.getAsDouble()));
		});

		// ---- liquidity and volume (Amihud 2002, Brennan et al. 1998) ----
		add("Illiquidity", "Amihud illiquidity", 240, null, (s, b, d) -> {
			List<Series.Bar> window = s.barsBetween(d.minusMonths(12), d);
			List<Double> rets = s.dailyReturns(d.minusMonths(12), d);
			if (rets.size() < 60 || window.size() < 60) {
				return OptionalDouble.empty();
			}
			List<Double> ratios = new ArrayList<>();
			int offset = window.size() - rets.size();
			for (int i = 0; i < rets.size(); i++) {
				Series.Bar bar = window.get(Math.min(window.size() - 1, i + offset));
				double dollarVolume = Math.abs(bar.close()) * bar.volume();
				if (dollarVolume > 0) {
					ratios.add(Math.abs(rets.get(i)) / dollarVolume);
				}
			}
			return ratios.size() < 60 ? OptionalDouble.empty() : Series.mean(ratios);
		});
		add("DolVol", "Log dollar trading volume, two months lagged", 60, null, (s, b, d) -> {
			List<Series.Month> ms = s.monthsBefore(d, 3);
			if (ms.isEmpty()) {
				return OptionalDouble.empty();
			}
			Series.Month m = ms.get(0); // oldest of the trailing three = the month two months back
			double dollar = m.volume() * m.close();
			return dollar <= 0 ? OptionalDouble.empty() : OptionalDouble.of(Math.log(dollar));
		});
		add("VolSD", "Standard deviation of monthly volume (36 months)", 500,
				"The paper restricts the sample to NYSE stocks; Argus applies it across the whole ranking universe.",
				(s, b, d) -> {
					List<Double> vols = new ArrayList<>();
					for (Series.Month m : s.monthsBefore(d, 36)) {
						vols.add(m.volume());
					}
					return vols.size() < 24 ? OptionalDouble.empty() : Series.stdDev(vols);
				});
		add("VolumeTrend", "Trend in monthly volume (60 months)", 760, null, (s, b, d) -> {
			List<Series.Month> ms = s.monthsBefore(d, 60);
			if (ms.size() < 30) {
				return OptionalDouble.empty();
			}
			List<Double> xs = new ArrayList<>();
			List<Double> ys = new ArrayList<>();
			for (int i = 0; i < ms.size(); i++) {
				xs.add((double) i);
				ys.add(ms.get(i).volume());
			}
			OptionalDouble slope = Series.slope(xs, ys);
			OptionalDouble avg = Series.mean(ys);
			return slope.isEmpty() || avg.isEmpty() || avg.getAsDouble() <= 0
					? OptionalDouble.empty() : OptionalDouble.of(slope.getAsDouble() / avg.getAsDouble());
		});

		// ---- market risk ----
		add("Beta", "Market beta (60 monthly returns)", 1050,
				"Regressed on raw SPY returns rather than excess returns over the risk-free rate (the subtraction "
						+ "barely moves a beta estimate), and on SPY rather than the CRSP equal-weighted index.",
				(s, b, d) -> {
					List<Series.MonthReturn> stock = s.monthlyReturns(d, 60);
					List<Series.MonthReturn> market = b == null ? List.of() : b.monthlyReturns(d, 60);
					if (stock.size() < 20 || market.isEmpty()) {
						return OptionalDouble.empty();
					}
					Map<java.time.YearMonth, Double> mkt = new LinkedHashMap<>();
					market.forEach(mr -> mkt.put(mr.month(), mr.ret()));
					List<Double> xs = new ArrayList<>();
					List<Double> ys = new ArrayList<>();
					for (Series.MonthReturn mr : stock) {
						Double m = mkt.get(mr.month());
						if (m != null) {
							xs.add(m);
							ys.add(mr.ret());
						}
					}
					return xs.size() < 20 ? OptionalDouble.empty() : Series.slope(xs, ys);
				});
	}

	/** E[r̃ᵢ r̃ₘ²] / (SD[r̃ᵢ] SD[r̃ₘ]²) over a year of daily data, both series de-meaned (Ang, Chen & Xing 2006). */
	private static OptionalDouble coskewness(Series s, Series benchmark, LocalDate asOf) {
		if (benchmark == null) {
			return OptionalDouble.empty();
		}
		Map<LocalDate, Double> stockByDate = new LinkedHashMap<>();
		List<Series.Bar> window = s.barsBetween(asOf.minusMonths(12), asOf);
		List<Double> stockRets = s.dailyReturns(asOf.minusMonths(12), asOf);
		int offset = window.size() - stockRets.size();
		for (int i = 0; i < stockRets.size(); i++) {
			stockByDate.put(window.get(Math.min(window.size() - 1, i + offset)).date(), stockRets.get(i));
		}
		List<Series.Bar> mWindow = benchmark.barsBetween(asOf.minusMonths(12), asOf);
		List<Double> mRets = benchmark.dailyReturns(asOf.minusMonths(12), asOf);
		int mOffset = mWindow.size() - mRets.size();
		List<Double> xs = new ArrayList<>();
		List<Double> ys = new ArrayList<>();
		for (int i = 0; i < mRets.size(); i++) {
			LocalDate date = mWindow.get(Math.min(mWindow.size() - 1, i + mOffset)).date();
			Double stockRet = stockByDate.get(date);
			if (stockRet != null) {
				ys.add(stockRet);
				xs.add(mRets.get(i));
			}
		}
		if (xs.size() < 100) {
			return OptionalDouble.empty();
		}
		double meanStock = Series.mean(ys).orElseThrow();
		double meanMkt = Series.mean(xs).orElseThrow();
		double sdStock = Series.stdDev(ys).orElse(0);
		double sdMkt = Series.stdDev(xs).orElse(0);
		if (sdStock <= 0 || sdMkt <= 0) {
			return OptionalDouble.empty();
		}
		double num = 0;
		for (int i = 0; i < xs.size(); i++) {
			num += (ys.get(i) - meanStock) * Math.pow(xs.get(i) - meanMkt, 2);
		}
		num /= xs.size();
		return OptionalDouble.of(num / (sdStock * sdMkt * sdMkt));
	}

	private void add(String acronym, String label, int minBars, String deviation, Computation c) {
		byAcronym.put(acronym, new Signal(acronym, label, minBars, deviation, c));
	}

	/** Every implemented signal, in registration order. */
	public List<Signal> all() {
		return List.copyOf(byAcronym.values());
	}

	public Optional<Signal> get(String acronym) {
		return Optional.ofNullable(byAcronym.get(acronym));
	}

	/** The implementation id for a corpus acronym, or empty when Argus cannot compute that signal. */
	public Optional<String> implementationFor(String acronym) {
		return get(acronym).map(Signal::acronym);
	}

	/** Acronyms this library implements per ticker (excludes aggregates like industry momentum). */
	public java.util.Set<String> implementedAcronyms() {
		return byAcronym.keySet();
	}
}
