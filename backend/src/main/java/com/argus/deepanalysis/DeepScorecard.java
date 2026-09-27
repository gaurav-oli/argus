package com.argus.deepanalysis;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Measures Agent 11 against reality. Every verdict is compared with what the stock did <em>after</em> it, relative to the
 * S&amp;P 500, at 7 / 30 / 90 days from the price when the analysis ran — the same benchmark-relative yardstick the paper
 * trades use. A WORTH_BUYING "hits" when the stock beats the market, NOT_WORTH_BUYING when it lags it; WAIT has no hit (it
 * makes no directional claim) and is reported by mean excess only. Pure: no I/O, so it can be checked against known series.
 *
 * <p>Horizons that have not yet elapsed are simply absent — the scorecard never scores a verdict on a period that has not happened.
 */
public final class DeepScorecard {

	public static final List<Integer> HORIZONS = List.of(7, 30, 90);

	private DeepScorecard() {
	}

	/** One analysis to evaluate. {@code entryPrice} may be null (the first close on/after the analysis date is used). */
	public record Sample(String ticker, DeepVerdict verdict, LocalDate analyzedOn, Double entryPrice) {
	}

	public record Bar(LocalDate date, double close) {
	}

	/**
	 * @param sincePct       stock return since the analysis, to the latest bar
	 * @param sinceExcessPct that minus the S&amp;P 500's over the same span
	 * @param matured        excess return vs the S&amp;P at each horizon that has elapsed (horizon days → percent)
	 */
	public record Row(String ticker, DeepVerdict verdict, LocalDate analyzedOn, Double entryPrice, Double sincePct, Double sinceSpyPct,
			Double sinceExcessPct, Map<Integer, Double> matured) {
	}

	/** Aggregate of one verdict at one horizon: how many have matured, their mean excess, and the directional hit rate (null for WAIT). */
	public record Cell(int n, double meanExcessPct, Double hitRate) {
	}

	public record Summary(List<Row> rows, Map<DeepVerdict, Map<Integer, Cell>> cells, int totalVerdicts) {
	}

	/** Evaluate one verdict against the stock's and the benchmark's daily closes (both ascending by date). */
	public static Row evaluate(Sample s, List<Bar> stock, List<Bar> spy) {
		Optional<Bar> entryBar = firstOnOrAfter(stock, s.analyzedOn());
		Optional<Bar> spyEntry = firstOnOrAfter(spy, s.analyzedOn());
		Double entry = s.entryPrice() != null ? s.entryPrice() : entryBar.map(Bar::close).orElse(null);
		Map<Integer, Double> matured = new LinkedHashMap<>();
		if (entry == null || entry <= 0 || spyEntry.isEmpty() || stock.isEmpty() || spy.isEmpty()) {
			return new Row(s.ticker(), s.verdict(), s.analyzedOn(), entry, null, null, null, matured);
		}
		double spy0 = spyEntry.get().close();
		Bar lastStock = stock.get(stock.size() - 1), lastSpy = spy.get(spy.size() - 1);
		double since = pct(lastStock.close(), entry), sinceSpy = pct(lastSpy.close(), spy0);
		for (int h : HORIZONS) {
			LocalDate target = s.analyzedOn().plusDays(h);
			Optional<Bar> stockExit = firstOnOrAfter(stock, target);
			Optional<Bar> spyExit = firstOnOrAfter(spy, target);
			if (stockExit.isPresent() && spyExit.isPresent()) {
				matured.put(h, pct(stockExit.get().close(), entry) - pct(spyExit.get().close(), spy0));
			}
		}
		return new Row(s.ticker(), s.verdict(), s.analyzedOn(), entry, since, sinceSpy, since - sinceSpy, matured);
	}

	/** Roll rows up to verdict × horizon cells. */
	public static Summary summarize(List<Row> rows) {
		Map<DeepVerdict, Map<Integer, Cell>> cells = new EnumMap<>(DeepVerdict.class);
		for (DeepVerdict v : DeepVerdict.values()) {
			Map<Integer, Cell> byHorizon = new LinkedHashMap<>();
			for (int h : HORIZONS) {
				List<Double> xs = new ArrayList<>();
				for (Row r : rows) {
					if (r.verdict() == v && r.matured().containsKey(h)) xs.add(r.matured().get(h));
				}
				if (xs.isEmpty()) continue;
				double mean = xs.stream().mapToDouble(Double::doubleValue).average().orElse(0);
				Double hit = null;
				if (v == DeepVerdict.WORTH_BUYING) hit = xs.stream().filter(x -> x > 0).count() / (double) xs.size();
				else if (v == DeepVerdict.NOT_WORTH_BUYING) hit = xs.stream().filter(x -> x < 0).count() / (double) xs.size();
				byHorizon.put(h, new Cell(xs.size(), mean, hit));
			}
			cells.put(v, byHorizon);
		}
		return new Summary(List.copyOf(rows), cells, rows.size());
	}

	private static Optional<Bar> firstOnOrAfter(List<Bar> bars, LocalDate date) {
		for (Bar b : bars) {
			if (!b.date().isBefore(date)) return Optional.of(b);
		}
		return Optional.empty();
	}

	private static double pct(double now, double then) {
		return (now / then - 1) * 100;
	}
}
