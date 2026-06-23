package com.argus.intelligence;

import com.argus.intelligence.MarketDataPort.MarketStats;
import java.math.BigDecimal;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Computes the 0–100 pump-and-dump risk score for a stranger ticker (Story 4.4, FR-10) from explicit,
 * auditable inputs — coverage burst, covering-source credibility, posting coordination, and (when
 * available) market fundamentals. Pure function of its inputs; no LLM. When market data is absent the
 * score is built from the news/credibility signals alone and is intentionally capped below 100,
 * reflecting the lower certainty.
 */
@Component
public class StrangerRiskScorer {

	private static final double COVERAGE_SATURATION = 10.0; // articles ⇒ full coverage weight
	private static final double COORD_RATIO_SATURATION = 4.0; // articles-per-source ⇒ full coordination
	private static final double VOLUME_SPIKE_SATURATION = 5.0; // ×average ⇒ full volume risk
	private static final long MICRO_CAP_USD = 300_000_000L;
	private static final long MID_CAP_USD = 2_000_000_000L;

	private static final double W_COVERAGE = 0.35;
	private static final double W_CREDIBILITY = 0.30;
	private static final double W_COORDINATION = 0.20;
	private static final double W_MARKET = 0.15;

	/**
	 * @param coverageCount    articles mentioning the stranger in the window
	 * @param distinctSources  distinct sources among those articles (few sources, many posts ⇒ coordinated)
	 * @param avgSourceScore   mean credibility (0–100) of the covering sources (low ⇒ riskier)
	 * @param stats            optional market fundamentals; absent degrades gracefully
	 */
	public int score(int coverageCount, int distinctSources, double avgSourceScore, Optional<MarketStats> stats) {
		double coverage = clamp01(coverageCount / COVERAGE_SATURATION);
		double lowCredibility = clamp01(1.0 - avgSourceScore / 100.0);
		double ratio = coverageCount / (double) Math.max(1, distinctSources);
		double coordination = clamp01((ratio - 1.0) / (COORD_RATIO_SATURATION - 1.0));

		double raw = W_COVERAGE * coverage + W_CREDIBILITY * lowCredibility + W_COORDINATION * coordination;
		if (stats.isPresent()) {
			raw += W_MARKET * marketRisk(stats.get());
		}
		return (int) Math.round(clamp01(raw) * 100);
	}

	private static double marketRisk(MarketStats s) {
		double capRisk = capRisk(s.marketCapUsd());
		double volumeRisk = clamp01((s.volumeSpikeRatio() - 1.0) / (VOLUME_SPIKE_SATURATION - 1.0));
		return (capRisk + volumeRisk) / 2.0;
	}

	private static double capRisk(BigDecimal marketCapUsd) {
		if (marketCapUsd == null) {
			return 0.0;
		}
		double cap = marketCapUsd.doubleValue();
		if (cap <= MICRO_CAP_USD) {
			return 1.0;
		}
		return cap <= MID_CAP_USD ? 0.5 : 0.0;
	}

	private static double clamp01(double v) {
		return Math.max(0.0, Math.min(1.0, v));
	}
}
