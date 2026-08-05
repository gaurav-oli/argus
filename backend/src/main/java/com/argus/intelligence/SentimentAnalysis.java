package com.argus.intelligence;

/**
 * Small-model assessment of an article (Story 4.2): directional {@code label}, a {@code score} in
 * [-1, 1] (strength + direction), {@code relevance} in [0, 1] (how material it is to the held
 * tickers), and {@code macro} — whether the model judges this broad macro/political/geopolitical news
 * (tariffs, central-bank policy, elections, war, sanctions, currency moves) rather than
 * company-specific coverage. {@code macro} supplements {@link MacroRelevanceTagger}'s keyword match
 * with real-world-knowledge judgment: a keyword list can never be exhaustive for "all geopolitical
 * news worldwide," so a genuinely macro story with none of the listed keywords (an unfamiliar
 * politician's name, a regional conflict, a currency this list doesn't name) still gets caught, at no
 * extra cost — it rides the same per-article call {@link SentimentAnalyzer} already makes.
 * {@link #neutral()} is the safe default when the model is unavailable or unparseable.
 */
public record SentimentAnalysis(SentimentLabel label, double score, double relevance, boolean macro) {

	public static SentimentAnalysis neutral() {
		return new SentimentAnalysis(SentimentLabel.NEUTRAL, 0.0, 0.0, false);
	}
}
