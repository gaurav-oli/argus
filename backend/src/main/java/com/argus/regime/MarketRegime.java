package com.argus.regime;

import java.time.Instant;
import java.util.Map;

/**
 * A point-in-time read of the broad market — what the tape is doing right now, which the headline-only
 * agents cannot see. Every field is nullable/absent-tolerant: when the data source is down the snapshot
 * is {@link #unavailable()} and every guard that depends on it simply stays off (fail open — the
 * recommender degrades to its old behaviour rather than inventing a regime).
 *
 * @param spy1d       S&P 500 (SPY) change today vs. the last close, %
 * @param qqq1d       Nasdaq-100 (QQQ) change today, %
 * @param vix         VIX level
 * @param vix1d       VIX change today, %
 * @param tnx5d       10-year yield (^TNX) change over ~5 sessions, relative %
 * @param sector1d    daily % change of each sector benchmark ETF, by benchmark symbol
 */
public record MarketRegime(Instant asOf, Double spy1d, Double qqq1d, Double vix, Double vix1d, Double tnx5d,
		Map<String, Double> sector1d) {

	/** A broad selloff day: the kind of Trump-speech / macro-shock drop that has often fully reversed within days. */
	static final double BROAD_SELLOFF_PCT = -1.2;
	static final double RISK_OFF_VIX = 25.0;
	static final double VIX_SPIKE_PCT = 12.0;
	static final double RATES_RISING_PCT = 4.0;

	public static MarketRegime unavailable() {
		return new MarketRegime(Instant.now(), null, null, null, null, null, Map.of());
	}

	public boolean available() {
		return spy1d != null;
	}

	public boolean broadSelloff() {
		return spy1d != null && spy1d <= BROAD_SELLOFF_PCT || qqq1d != null && qqq1d <= BROAD_SELLOFF_PCT * 1.4;
	}

	public boolean riskOff() {
		return vix != null && (vix >= RISK_OFF_VIX || vix1d != null && vix1d >= VIX_SPIKE_PCT);
	}

	public boolean ratesRising() {
		return tnx5d != null && tnx5d >= RATES_RISING_PCT;
	}

	public boolean riskOn() {
		return spy1d != null && spy1d >= 0.5 && (vix == null || vix < 18.0);
	}

	public Double sectorMove(Sector sector) {
		return sector1d.get(sector.benchmark());
	}

	/** Short state name for the UI. */
	public String label() {
		if (!available()) return "UNKNOWN";
		if (broadSelloff() || riskOff()) return "RISK_OFF";
		if (riskOn()) return "RISK_ON";
		return "NEUTRAL";
	}

	/** One human-readable line: "S&P −1.8% today · VIX 27 · 10y yield rising". */
	public String summary() {
		if (!available()) return "Market data unavailable";
		StringBuilder sb = new StringBuilder(String.format("S&P %+.1f%% today", spy1d));
		if (vix != null) sb.append(String.format(" · VIX %.0f", vix));
		if (ratesRising()) sb.append(" · 10y yield rising");
		if (broadSelloff()) sb.append(" · broad selloff");
		return sb.toString();
	}
}
