package com.argus.portfolio;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.apache.commons.math3.stat.correlation.PearsonsCorrelation;

/**
 * Pure, auditable Pearson correlation of daily returns between two price series, restricted to the
 * trading days they actually share — the same deterministic read {@link HealthScoreService} uses to
 * flag a correlated pair in a real portfolio, factored out as a pairwise primitive so anything else
 * that needs to ask "do these two tickers move together?" (the paper book's own concentration guard,
 * for one) doesn't have to duplicate the math. No LLM involved: like every other risk number in this
 * app, this is computed, never guessed.
 */
public final class CorrelationRisk {

	private CorrelationRisk() {
	}

	public record Bar(LocalDate date, double close) {
	}

	/**
	 * Pearson correlation of daily returns over the dates both series share, oldest first in each.
	 * Empty when fewer than {@code minCommonDays} days overlap — a correlation read on too little
	 * shared history is noise, not signal, same discipline as every other data-driven read in this app
	 * — or when either series is degenerate (zero variance), which Pearson correlation can't answer.
	 */
	public static Optional<Double> correlation(List<Bar> a, List<Bar> b, int minCommonDays) {
		Map<LocalDate, Double> byDateA = a.stream().collect(Collectors.toMap(Bar::date, Bar::close, (x, y) -> x));
		Map<LocalDate, Double> byDateB = b.stream().collect(Collectors.toMap(Bar::date, Bar::close, (x, y) -> x));
		List<LocalDate> common = byDateA.keySet().stream().filter(byDateB::containsKey).sorted().toList();
		if (common.size() < minCommonDays) {
			return Optional.empty();
		}
		double[] returnsA = new double[common.size() - 1];
		double[] returnsB = new double[common.size() - 1];
		for (int i = 1; i < common.size(); i++) {
			returnsA[i - 1] = pctChange(byDateA.get(common.get(i - 1)), byDateA.get(common.get(i)));
			returnsB[i - 1] = pctChange(byDateB.get(common.get(i - 1)), byDateB.get(common.get(i)));
		}
		double c = new PearsonsCorrelation().correlation(returnsA, returnsB);
		return Double.isNaN(c) ? Optional.empty() : Optional.of(c);
	}

	private static double pctChange(double prev, double curr) {
		return (curr - prev) / prev;
	}
}
