package com.argus.learning;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import tools.jackson.databind.json.JsonMapper;

/**
 * The shared vocabulary for describing "the situation a call was made in" as a set of string tokens —
 * {@code dir=BULLISH}, {@code sector=SEMICONDUCTORS}, {@code regime=RISK_OFF}, {@code has=SOCIAL},
 * {@code lead=NEWS}, {@code trend=UPTREND}, {@code deep=WORTH_BUYING}, {@code conv=70-79}, and so on. Tokens
 * are what get stored on a recommendation, what the Trade Learner mines outcomes against, and what a
 * {@link LearnedRule} matches — so a lesson learned about one kind of situation applies to exactly that
 * kind of situation everywhere.
 */
public final class FeatureTokens {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private FeatureTokens() {
	}

	public static String convictionBucket(int score) {
		return score >= 80 ? "80+" : score >= 70 ? "70-79" : score >= 62 ? "62-69" : "under62";
	}

	public static String priceBucket(Double price) {
		if (price == null) return null;
		return price < 10 ? "under10" : price < 50 ? "10-50" : price < 200 ? "50-200" : "200+";
	}

	public static String volatilityBucket(Double atrPct) {
		if (atrPct == null) return null;
		return atrPct < 2 ? "low" : atrPct < 4 ? "mid" : "high";
	}

	public static String horizonToken(int days) {
		return "hold=" + days;
	}

	/** Serialize tokens as a JSON array (sorted for stable storage and diffing). */
	public static String toJson(Collection<String> tokens) {
		return JSON.writeValueAsString(new java.util.TreeSet<>(tokens));
	}

	/** Parse stored tokens; empty for null/blank/malformed input. */
	public static Set<String> fromJson(String json) {
		if (json == null || json.isBlank()) {
			return Set.of();
		}
		try {
			List<String> list = JSON.readValue(json, new tools.jackson.core.type.TypeReference<List<String>>() {
			});
			return new LinkedHashSet<>(list);
		}
		catch (RuntimeException ex) {
			return Set.of();
		}
	}

	/**
	 * {@code has=GROUP} for every evidence group that supports the call, and {@code lead=GROUP} for the one
	 * carrying the most weight — the "what was this call actually resting on" part of a situation.
	 */
	public static Set<String> groupTokens(java.util.Map<SignalGroup, Double> alignedWeights) {
		Set<String> out = new LinkedHashSet<>();
		SignalGroup lead = null;
		double best = 0;
		for (var e : alignedWeights.entrySet()) {
			if (e.getValue() <= 0) continue;
			out.add("has=" + e.getKey().name());
			if (e.getValue() > best) {
				best = e.getValue();
				lead = e.getKey();
			}
		}
		if (lead != null) {
			out.add("lead=" + lead.name());
		}
		return out;
	}

	/** Add a token only when its value is known. */
	public static void addIfPresent(Set<String> tokens, String key, Object value) {
		if (value != null && !value.toString().isBlank()) {
			tokens.add(key + "=" + value);
		}
	}
}
