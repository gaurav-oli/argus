package com.argus.learning;

import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Reconstructs the situation tokens of a trade made before recommendations stored them (everything opened
 * before the learning system existed — the bulk of the first paper book). Only what is genuinely recoverable
 * from the stored record is emitted: direction, sector, horizon, ticker, entry-price bucket, and which evidence
 * groups supported the call (and which led). Regime, chart and deep-analysis tokens are simply absent — never
 * guessed — so old trades can teach about sector/evidence patterns but not about market conditions.
 */
public final class LegacyTokens {

	private static final double MAX_SIGNAL_CONTRIBUTION = 0.9;

	private LegacyTokens() {
	}

	/** One stored signal: its agent id, direction name and weight. */
	public record Sig(String agent, String direction, double weight) {
	}

	public static Set<String> build(String direction, String sectorName, int horizonDays, String ticker, Double entryPrice, Iterable<Sig> signals) {
		Set<String> t = new LinkedHashSet<>();
		t.add("dir=" + direction);
		if (sectorName != null) t.add("sector=" + sectorName);
		t.add(FeatureTokens.horizonToken(horizonDays));
		t.add("ticker=" + ticker);
		FeatureTokens.addIfPresent(t, "price", FeatureTokens.priceBucket(entryPrice));
		Map<SignalGroup, Double> aligned = new EnumMap<>(SignalGroup.class);
		for (Sig s : signals) {
			SignalGroup g = SignalGroup.of(s.agent());
			if (g == SignalGroup.CALENDAR || !direction.equals(s.direction()) || s.weight() <= 0) continue;
			aligned.merge(g, Math.min(s.weight(), MAX_SIGNAL_CONTRIBUTION), Double::sum);
		}
		t.addAll(FeatureTokens.groupTokens(aligned));
		return t;
	}
}
