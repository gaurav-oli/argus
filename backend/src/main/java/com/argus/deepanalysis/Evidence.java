package com.argus.deepanalysis;

import com.argus.fundamentals.Fundamentals;
import com.argus.learning.FeatureTokens;
import com.argus.learning.SignalGroup;
import com.argus.recommendation.AgentSignal;
import com.argus.regime.MarketRegime;
import com.argus.regime.Sector;
import com.argus.technical.ChartStudy;
import java.util.List;
import java.util.Locale;

/**
 * Everything Agent 11 knows about a stock before any LLM is involved — collected deterministically from the
 * other agents and data sources by {@link EvidenceCollector}. Each analyst is handed only the slice
 * relevant to its specialty, and the whole pack is stored with the analysis so any verdict can be audited
 * against exactly what the analysts saw.
 *
 * @param etf                     true when there is no company data (ETF/fund/unrecognised) — fundamentals do not apply
 * @param earningsInTradingDays   trading days to the next earnings release, or null when none is near
 * @param quickSignals            what the fast agents (news, macro, crowd, insider, web, calendar, chart, fundamentals) concluded
 */
public record Evidence(String ticker, Sector sector, boolean etf, Double lastPrice, ChartStudy chart, Fundamentals fundamentals,
		List<AgentSignal> quickSignals, String newsBlock, String insiderBlock, String crowdBlock, String earningsBlock,
		Integer earningsInTradingDays, String macroBlock, MarketRegime regime) {

	/**
	 * The situation this stock is in, as the same feature tokens the recommender and the Trade Learner use — evaluated
	 * as a would-be <em>buy</em> (Agent 11's question is always "is this worth buying?"), so the lessons learned from past
	 * bullish trades in similar situations can speak to it.
	 */
	public java.util.Set<String> featureTokens() {
		java.util.Set<String> t = new java.util.LinkedHashSet<>();
		t.add("dir=BULLISH");
		t.add("sector=" + sector.name());
		t.add("ticker=" + ticker);
		if (regime != null && regime.available()) t.add("regime=" + regime.label());
		if (chart != null) {
			t.add("trend=" + chart.trend().name());
			t.add("chart=" + chart.bias());
			FeatureTokens.addIfPresent(t, "vol", FeatureTokens.volatilityBucket(chart.atrPct()));
		}
		FeatureTokens.addIfPresent(t, "price", FeatureTokens.priceBucket(lastPrice));
		if (earningsInTradingDays != null && earningsInTradingDays <= 5) t.add("earnings=soon");
		java.util.Map<SignalGroup, Double> weights = new java.util.EnumMap<>(SignalGroup.class);
		for (AgentSignal s : quickSignals) {
			if (s.direction() == com.argus.recommendation.SignalDirection.BULLISH) {
				weights.merge(SignalGroup.of(s.agent()), Math.min(s.weight(), 0.9), Double::sum);
			}
		}
		t.addAll(FeatureTokens.groupTokens(weights));
		return t;
	}

	/** Who the stock is and where it stands — given to every analyst. */
	public String overview() {
		StringBuilder sb = new StringBuilder();
		sb.append(String.format(Locale.ROOT, "Ticker %s — sector: %s%s. Last price: %s.%n", ticker, sector.label(),
				etf ? " (ETF/fund: no company fundamentals)" : "", lastPrice == null ? "unknown" : String.format(Locale.ROOT, "%.2f", lastPrice)));
		sb.append("Market backdrop: ").append(regime == null ? "unknown" : regime.summary()).append(".\n");
		if (chart != null && chart.drawdown60dPct() != null) {
			sb.append(String.format(Locale.ROOT, "The stock is %.1f%% below its 60-day high.%n", Math.abs(chart.drawdown60dPct())));
		}
		return sb.toString();
	}

	/** What the fast agents concluded, one line each. */
	public String quickAgents() {
		if (quickSignals.isEmpty()) {
			return "The fast agents produced no directional signals for this stock.\n";
		}
		StringBuilder sb = new StringBuilder();
		for (AgentSignal s : quickSignals) {
			sb.append(String.format(Locale.ROOT, "- %s: %s (weight %.2f) — %s%n", s.agent(), s.direction(), s.weight(), s.rationale()));
		}
		return sb.toString();
	}

	public String technicalSection() {
		return overview() + (chart == null ? "No chart study: not enough price history is stored yet.\n" : chart.render());
	}

	public String fundamentalSection() {
		return overview() + (fundamentals == null ? "Fundamentals: not available.\n" : fundamentals.render());
	}

	public String catalystSection() {
		return overview() + "RECENT NEWS (company-specific, most relevant first):\n" + newsBlock + "\nINSIDER ACTIVITY:\n" + insiderBlock
				+ "\nCROWD / WEB ATTENTION:\n" + crowdBlock + "\nEARNINGS:\n" + earningsBlock;
	}

	public String macroSection() {
		return overview() + macroBlock;
	}

	/** The complete pack as stored on the analysis row. */
	public String fullText() {
		return "=== OVERVIEW ===\n" + overview() + "\n=== WHAT THE FAST AGENTS CONCLUDED ===\n" + quickAgents()
				+ "\n=== CHART (Agent 10) ===\n" + (chart == null ? "No chart study available.\n" : chart.render())
				+ "\n=== FUNDAMENTALS (Agent 12) ===\n" + (fundamentals == null ? "Not available.\n" : fundamentals.render())
				+ "\n=== NEWS ===\n" + newsBlock + "\n=== INSIDERS ===\n" + insiderBlock + "\n=== CROWD / WEB ===\n" + crowdBlock
				+ "\n=== EARNINGS ===\n" + earningsBlock + "\n=== MACRO & SECTOR ===\n" + macroBlock;
	}
}
