package com.argus.intelligence;

/**
 * Small-model assessment of an article (Story 4.2): directional {@code label}, a {@code score} in
 * [-1, 1] (strength + direction), and {@code relevance} in [0, 1] (how material it is to the held
 * tickers). {@link #neutral()} is the safe default when the model is unavailable or unparseable.
 */
public record SentimentAnalysis(SentimentLabel label, double score, double relevance) {

	public static SentimentAnalysis neutral() {
		return new SentimentAnalysis(SentimentLabel.NEUTRAL, 0.0, 0.0);
	}
}
