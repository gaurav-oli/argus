package com.argus.fundamentals;

import com.argus.fundamentals.Fundamentals.AnalystConsensus;
import com.argus.fundamentals.Fundamentals.EarningsSurprise;
import com.argus.fundamentals.Fundamentals.PeerComparison;
import com.argus.fundamentals.Fundamentals.Quarter;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import tools.jackson.databind.JsonNode;

/**
 * Pure fundamental analysis over Finnhub payloads — no I/O, no LLM. Reads the reported quarterly income
 * statements for growth and margin trends (year-over-year, matched to the quarter a year earlier so
 * seasonality doesn't masquerade as growth), the ratio set for leverage and valuation, the last four
 * earnings surprises, the analyst rating distribution and its direction of change, and valuation against
 * a peer-median P/E. The {@link Fundamentals#score()} is a fixed, documented blend of those.
 *
 * <p>Honest about gaps: banks/insurers report no plain "revenue" line and ETFs have no company data, so
 * every input is optional and each missing piece simply contributes nothing — it is never estimated.
 */
public final class FundamentalsAnalyzer {

	private static final List<String> REVENUE = List.of("Revenues", "RevenueFromContractWithCustomerExcludingAssessedTax",
			"RevenueFromContractWithCustomerIncludingAssessedTax", "SalesRevenueNet", "SalesRevenueGoodsNet");
	private static final int MAX_QUARTERS = 8;

	private FundamentalsAnalyzer() {
	}

	/**
	 * @param profile         {@code /stock/profile2} body (empty object for ETFs/unknown symbols)
	 * @param metric          the {@code metric} object of {@code /stock/metric?metric=all}
	 * @param financials      {@code /stock/financials-reported?freq=quarterly} body
	 * @param earnings        {@code /stock/earnings} body (array)
	 * @param recommendations {@code /stock/recommendation} body (array, newest first)
	 * @param peerPes         trailing P/E of the peer companies that reported one
	 */
	public static Fundamentals analyze(String ticker, JsonNode profile, JsonNode metric, JsonNode financials,
			JsonNode earnings, JsonNode recommendations, List<String> peers, List<Double> peerPes) {
		String name = text(profile, "name");
		Double marketCap = num(profile, "marketCapitalization");
		if (name == null && marketCap == null && (metric == null || metric.isMissingNode() || metric.isNull()
				|| !metric.properties().iterator().hasNext())) {
			return Fundamentals.notApplicable(ticker, "No company data — likely an ETF/fund or an unrecognised symbol.");
		}

		List<String> notes = new ArrayList<>();
		double score = 0;
		Map<String, Double> ratios = new LinkedHashMap<>();
		for (String key : List.of("peTTM", "forwardPE", "psTTM", "pbQuarterly", "evEbitdaTTM", "grossMarginTTM",
				"operatingMarginTTM", "netProfitMarginTTM", "roeTTM", "totalDebt/totalEquityQuarterly",
				"currentRatioQuarterly", "revenueGrowthTTMYoy", "revenueGrowthQuarterlyYoy", "epsGrowthTTMYoy",
				"epsGrowthQuarterlyYoy", "beta", "52WeekHigh", "52WeekLow")) {
			Double v = num(metric, key);
			if (v != null) {
				ratios.put(key, v);
			}
		}

		List<Quarter> quarters = quarters(financials);

		// ---- growth (statements first, ratio fallback) ----
		Double revYoy = yoyRevenue(quarters, 0);
		Double revYoyPrev = yoyRevenue(quarters, 1);
		if (revYoy == null) {
			revYoy = ratios.get("revenueGrowthQuarterlyYoy") != null ? ratios.get("revenueGrowthQuarterlyYoy")
					: ratios.get("revenueGrowthTTMYoy");
		}
		if (revYoy != null) {
			String trend = revYoyPrev == null ? "" : revYoy > revYoyPrev + 2 ? ", accelerating" : revYoy < revYoyPrev - 2 ? ", decelerating" : ", steady";
			notes.add(String.format(Locale.ROOT, "Growth: revenue %+.1f%% year-over-year in the latest quarter%s.", revYoy,
					revYoyPrev == null ? "" : String.format(Locale.ROOT, " (prior quarter %+.1f%%)", revYoyPrev) + trend));
			score += revYoy >= 20 ? 0.25 : revYoy >= 8 ? 0.15 : revYoy >= 0 ? 0.05 : revYoy >= -10 ? -0.15 : -0.25;
			if (revYoyPrev != null) {
				score += revYoy > revYoyPrev + 2 ? 0.03 : revYoy < revYoyPrev - 2 ? -0.03 : 0;
			}
		}
		Double epsGrowth = ratios.get("epsGrowthTTMYoy");
		if (epsGrowth != null) {
			notes.add(String.format(Locale.ROOT, "Earnings growth: EPS %+.1f%% year-over-year (trailing 12 months).", epsGrowth));
		}

		// ---- profitability ----
		if (!quarters.isEmpty() && quarters.get(0).revenue() != null && quarters.get(0).revenue() > 0) {
			Quarter q = quarters.get(0);
			Double netMargin = q.netIncome() == null ? null : q.netIncome() / q.revenue() * 100;
			Double opMargin = q.operatingIncome() == null ? null : q.operatingIncome() / q.revenue() * 100;
			Optional<Quarter> yearAgo = yearAgo(quarters, 0);
			Double netMarginPrior = yearAgo.filter(y -> y.revenue() != null && y.revenue() > 0 && y.netIncome() != null)
					.map(y -> y.netIncome() / y.revenue() * 100).orElse(null);
			if (netMargin != null) {
				String move = netMarginPrior == null ? ""
						: String.format(Locale.ROOT, " vs %.1f%% a year ago (%s)", netMarginPrior,
								netMargin - netMarginPrior >= 1 ? "expanding" : netMargin - netMarginPrior <= -2 ? "contracting" : "stable");
				notes.add(String.format(Locale.ROOT, "Profitability: net margin %.1f%%%s%s.", netMargin, move,
						opMargin == null ? "" : String.format(Locale.ROOT, ", operating margin %.1f%%", opMargin)));
				if (netMargin < 0) {
					score -= 0.15;
					notes.add("The company is loss-making in the latest quarter.");
				}
				else {
					score += netMargin >= 20 ? 0.10 : netMargin >= 8 ? 0.05 : 0;
				}
				if (netMarginPrior != null) {
					score += netMargin - netMarginPrior >= 1 ? 0.05 : netMargin - netMarginPrior <= -2 ? -0.05 : 0;
				}
			}
		}
		else if (ratios.get("netProfitMarginTTM") != null) {
			double m = ratios.get("netProfitMarginTTM");
			notes.add(String.format(Locale.ROOT, "Profitability: trailing net margin %.1f%%.", m));
			score += m < 0 ? -0.15 : m >= 20 ? 0.10 : m >= 8 ? 0.05 : 0;
		}

		// ---- balance sheet ----
		Double de = ratios.get("totalDebt/totalEquityQuarterly");
		Double cr = ratios.get("currentRatioQuarterly");
		if (de != null || cr != null) {
			notes.add(String.format(Locale.ROOT, "Balance sheet: debt/equity %s, current ratio %s.",
					de == null ? "n/a" : String.format(Locale.ROOT, "%.2f", de), cr == null ? "n/a" : String.format(Locale.ROOT, "%.2f", cr)));
			if (de != null) score += de > 2 ? -0.10 : de < 0.5 ? 0.05 : 0;
			if (cr != null && cr < 1) score -= 0.05;
		}

		// ---- earnings track record ----
		List<EarningsSurprise> surprises = surprises(earnings);
		if (!surprises.isEmpty()) {
			long beats = surprises.stream().filter(s -> s.surprisePct() != null && s.surprisePct() > 0).count();
			double avg = surprises.stream().filter(s -> s.surprisePct() != null).mapToDouble(EarningsSurprise::surprisePct).average().orElse(0);
			notes.add(String.format(Locale.ROOT, "Earnings: beat EPS estimates in %d of the last %d quarters (average surprise %+.1f%%).",
					beats, surprises.size(), avg));
			if (surprises.size() >= 3) {
				double share = (double) beats / surprises.size();
				score += share >= 0.99 ? 0.15 : share >= 0.7 ? 0.08 : share <= 0.25 ? -0.10 : 0;
				if (avg < 0) score -= 0.05;
			}
		}

		// ---- analyst consensus ----
		AnalystConsensus analysts = analysts(recommendations);
		if (analysts != null && analysts.bullishShare() != null) {
			notes.add(String.format(Locale.ROOT, "Analysts (%s): %d strong buy / %d buy / %d hold / %d sell / %d strong sell — %.0f%% bullish%s.",
					analysts.period(), analysts.strongBuy(), analysts.buy(), analysts.hold(), analysts.sell(), analysts.strongSell(),
					analysts.bullishShare() * 100, analysts.previousBullishShare() == null ? ""
							: analysts.bullishShare() > analysts.previousBullishShare() + 0.02 ? ", improving vs the prior month"
							: analysts.bullishShare() < analysts.previousBullishShare() - 0.02 ? ", deteriorating vs the prior month" : ""));
			score += analysts.bullishShare() >= 0.7 ? 0.10 : analysts.bullishShare() <= 0.4 ? -0.10 : 0;
			if (analysts.previousBullishShare() != null) {
				score += analysts.bullishShare() > analysts.previousBullishShare() + 0.02 ? 0.03
						: analysts.bullishShare() < analysts.previousBullishShare() - 0.02 ? -0.03 : 0;
			}
		}

		// ---- valuation vs peers ----
		Double pe = ratios.get("peTTM");
		PeerComparison peerCmp = null;
		List<Double> pes = peerPes == null ? List.of() : peerPes.stream().filter(p -> p != null && p > 0).sorted().toList();
		if (pe != null && pe > 0 && pes.size() >= 2) {
			double median = pes.size() % 2 == 1 ? pes.get(pes.size() / 2) : (pes.get(pes.size() / 2 - 1) + pes.get(pes.size() / 2)) / 2;
			double premium = (pe / median - 1) * 100;
			peerCmp = new PeerComparison(peers == null ? List.of() : peers, median, pe, premium);
			notes.add(String.format(Locale.ROOT, "Valuation: P/E %.1f vs peer median %.1f (%s by %.0f%%)%s.", pe, median,
					premium >= 0 ? "premium" : "discount", Math.abs(premium), forwardPeNote(ratios)));
			score += premium > 50 ? -0.10 : premium < -20 ? 0.05 : 0;
		}
		else if (pe != null) {
			notes.add(String.format(Locale.ROOT, "Valuation: trailing P/E %.1f%s (no usable peer comparison).", pe, forwardPeNote(ratios)));
		}
		if (pe != null && pe > 60 && (revYoy == null || revYoy < 25)) {
			score -= 0.10;
			notes.add("Rich multiple without hyper-growth to justify it.");
		}
		if (pe != null && pe < 0) {
			notes.add("Trailing P/E is negative (losses), so earnings-based valuation does not apply.");
		}

		score = Math.max(-1.0, Math.min(1.0, score));
		String bias = score >= 0.2 ? "BULLISH" : score <= -0.2 ? "BEARISH" : "NEUTRAL";
		return new Fundamentals(ticker, true, name, text(profile, "finnhubIndustry"), marketCap, Map.copyOf(ratios),
				List.copyOf(quarters), List.copyOf(surprises), analysts, peerCmp, score, bias, List.copyOf(notes), Instant.now());
	}

	// ---- statements ----

	static List<Quarter> quarters(JsonNode financials) {
		List<Quarter> out = new ArrayList<>();
		if (financials == null) return out;
		for (JsonNode r : financials.path("data")) {
			if (!"10-Q".equalsIgnoreCase(r.path("form").asString(""))) continue;
			LocalDate end = date(r.path("endDate").asString(""));
			if (end == null) continue;
			JsonNode ic = r.path("report").path("ic");
			out.add(new Quarter(end, concept(ic, REVENUE), concept(ic, List.of("GrossProfit")),
					concept(ic, List.of("OperatingIncomeLoss")), concept(ic, List.of("NetIncomeLoss", "ProfitLoss"))));
		}
		out.sort(Comparator.comparing(Quarter::endDate).reversed());
		return out.size() > MAX_QUARTERS ? new ArrayList<>(out.subList(0, MAX_QUARTERS)) : out;
	}

	/** Revenue growth of {@code quarters[index]} vs the same quarter a year earlier (matched by date, ±25 days). */
	private static Double yoyRevenue(List<Quarter> quarters, int index) {
		if (index >= quarters.size()) return null;
		Quarter q = quarters.get(index);
		Optional<Quarter> prior = yearAgo(quarters, index);
		if (q.revenue() == null || prior.isEmpty() || prior.get().revenue() == null || prior.get().revenue() <= 0) return null;
		return (q.revenue() / prior.get().revenue() - 1) * 100;
	}

	private static Optional<Quarter> yearAgo(List<Quarter> quarters, int index) {
		LocalDate target = quarters.get(index).endDate().minusYears(1);
		return quarters.stream().filter(p -> Math.abs(ChronoUnit.DAYS.between(p.endDate(), target)) <= 25).findFirst();
	}

	/** The first income-statement line whose concept (after the {@code us-gaap_} style prefix) matches, in preference order. */
	private static Double concept(JsonNode items, List<String> names) {
		for (String want : names) {
			for (JsonNode i : items) {
				String c = i.path("concept").asString("");
				String bare = c.contains("_") ? c.substring(c.indexOf('_') + 1) : c;
				if (bare.equals(want) && i.path("value").isNumber()) {
					return i.path("value").asDouble();
				}
			}
		}
		return null;
	}

	// ---- earnings / analysts ----

	private static List<EarningsSurprise> surprises(JsonNode earnings) {
		List<EarningsSurprise> out = new ArrayList<>();
		if (earnings == null) return out;
		for (JsonNode e : earnings) {
			out.add(new EarningsSurprise(e.path("period").asString(""), num(e, "actual"), num(e, "estimate"), num(e, "surprisePercent")));
		}
		return out;
	}

	private static AnalystConsensus analysts(JsonNode recs) {
		if (recs == null || !recs.isArray() || recs.isEmpty()) return null;
		JsonNode cur = recs.get(0);
		Double share = share(cur);
		Double prev = recs.size() > 1 ? share(recs.get(1)) : null;
		return new AnalystConsensus(cur.path("period").asString(""), cur.path("strongBuy").asInt(0), cur.path("buy").asInt(0),
				cur.path("hold").asInt(0), cur.path("sell").asInt(0), cur.path("strongSell").asInt(0), share, prev);
	}

	private static Double share(JsonNode r) {
		int total = r.path("strongBuy").asInt(0) + r.path("buy").asInt(0) + r.path("hold").asInt(0) + r.path("sell").asInt(0)
				+ r.path("strongSell").asInt(0);
		return total == 0 ? null : (double) (r.path("strongBuy").asInt(0) + r.path("buy").asInt(0)) / total;
	}

	// ---- small helpers ----

	private static String forwardPeNote(Map<String, Double> ratios) {
		Double f = ratios.get("forwardPE");
		return f == null ? "" : String.format(Locale.ROOT, "; forward P/E %.1f", f);
	}

	private static String text(JsonNode n, String field) {
		if (n == null) return null;
		JsonNode v = n.path(field);
		return v.isMissingNode() || v.isNull() || v.asString("").isBlank() ? null : v.asString();
	}

	private static Double num(JsonNode n, String field) {
		if (n == null) return null;
		JsonNode v = n.path(field);
		return v.isNumber() ? v.asDouble() : null;
	}

	private static LocalDate date(String s) {
		try {
			return s.length() >= 10 ? LocalDate.parse(s.substring(0, 10)) : null;
		}
		catch (RuntimeException ex) {
			return null;
		}
	}
}
