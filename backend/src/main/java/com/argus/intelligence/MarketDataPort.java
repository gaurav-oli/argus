package com.argus.intelligence;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * Seam for per-ticker market fundamentals used by the pump-and-dump risk score (Story 4.4): market
 * cap and a recent-vs-average volume signal. No implementation ships yet — the Stranger Danger
 * detector injects this optionally and degrades gracefully when absent, so the score is computed
 * from news/credibility heuristics until a Finnhub {@code /stock/profile2} + {@code /quote} client
 * is wired (a later story).
 */
public interface MarketDataPort {

	Optional<MarketStats> statsFor(String ticker);

	/**
	 * @param marketCapUsd market capitalization in USD (small caps are easier to pump)
	 * @param recentVolume most-recent session volume
	 * @param averageVolume trailing average volume (recent ≫ average ⇒ volume spike)
	 */
	record MarketStats(BigDecimal marketCapUsd, long recentVolume, long averageVolume) {

		/** Recent volume as a multiple of the trailing average (1.0 = normal); 1.0 if unknown. */
		public double volumeSpikeRatio() {
			return averageVolume <= 0 ? 1.0 : (double) recentVolume / averageVolume;
		}
	}
}
