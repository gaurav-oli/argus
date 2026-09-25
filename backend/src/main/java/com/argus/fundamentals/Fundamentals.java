package com.argus.fundamentals;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A company's fundamental picture — growth, profitability, balance sheet, earnings track record, analyst
 * consensus and valuation versus peers — assembled and scored deterministically by {@link
 * FundamentalsAnalyzer}. Until this existed, no Argus agent did fundamental analysis at all (Agent 4 reads
 * insider filings only; Agent 9 fetched a few ratios on demand and was never part of the recommender).
 *
 * <p>{@code applicable} is false for ETFs, funds and symbols with no company data; those get no score
 * (0) and no signal rather than an invented one.
 *
 * @param score -1 (fundamentally weak) .. +1 (fundamentally strong): a fixed weighted blend
 */
public record Fundamentals(String ticker, boolean applicable, String name, String industry, Double marketCapMillions,
		Map<String, Double> ratios, List<Quarter> quarters, List<EarningsSurprise> earnings, AnalystConsensus analysts,
		PeerComparison peers, double score, String bias, List<String> notes, Instant fetchedAt) {

	/** One reported quarter's income statement (values in the reporting currency's units, as filed). */
	public record Quarter(LocalDate endDate, Double revenue, Double grossProfit, Double operatingIncome, Double netIncome) {
	}

	public record EarningsSurprise(String period, Double actual, Double estimate, Double surprisePct) {
	}

	/** @param bullishShare (strong buy + buy) / all ratings, 0..1 */
	public record AnalystConsensus(String period, int strongBuy, int buy, int hold, int sell, int strongSell,
			Double bullishShare, Double previousBullishShare) {
	}

	/** @param premiumPct this company's P/E vs. the peer median, in percent (positive = trades at a premium) */
	public record PeerComparison(List<String> peers, Double medianPe, Double pe, Double premiumPct) {
	}

	public static Fundamentals notApplicable(String ticker, String reason) {
		return new Fundamentals(ticker, false, null, null, null, Map.of(), List.of(), List.of(), null, null, 0.0,
				"NEUTRAL", List.of(reason), Instant.now());
	}

	/** Multi-line plain-text rendering for an LLM prompt or a UI panel. */
	public String render() {
		StringBuilder sb = new StringBuilder();
		if (!applicable) {
			return "Fundamentals: not applicable — " + (notes.isEmpty() ? "no company data" : notes.get(0)) + "\n";
		}
		sb.append(String.format(Locale.ROOT, "Fundamentals for %s (%s, %s). Deterministic fundamental score %+.2f (%s).%n",
				ticker, name == null ? "?" : name, industry == null ? "industry n/a" : industry, score, bias));
		for (String n : notes) {
			sb.append("- ").append(n).append('\n');
		}
		return sb.toString();
	}
}
