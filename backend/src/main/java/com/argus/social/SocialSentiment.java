package com.argus.social;

import com.argus.intelligence.SentimentLabel;
import java.math.BigDecimal;
import java.util.Set;

/**
 * Lightweight crowd-post sentiment for Agent 2: prefer the source's own tag (StockTwits), else a
 * fast keyword classifier tuned for short cashtag posts (no model call — social runs at volume).
 */
public final class SocialSentiment {

	private static final Set<String> BULLISH = Set.of("buy", "buying", "bought", "long", "calls", "call",
			"bull", "bullish", "moon", "rocket", "breakout", "rip", "rips", "up", "green", "undervalued",
			"pump", "rally", "🚀", "📈", "💎");
	private static final Set<String> BEARISH = Set.of("sell", "selling", "sold", "short", "shorting", "puts",
			"put", "bear", "bearish", "dump", "dumping", "crash", "drop", "down", "red", "overvalued", "rug",
			"tank", "tanking", "📉", "🩸");

	private SocialSentiment() {
	}

	/** Resolve a post's sentiment: the source hint wins, else classify the body. */
	static SentimentLabel resolve(SentimentLabel hint, String body) {
		return hint != null ? hint : classify(body);
	}

	public static SentimentLabel classify(String body) {
		if (body == null || body.isBlank()) {
			return SentimentLabel.NEUTRAL;
		}
		String lower = body.toLowerCase();
		int bull = 0;
		int bear = 0;
		for (String w : lower.split("[^a-z🚀📈💎📉🩸]+")) {
			if (BULLISH.contains(w)) {
				bull++;
			}
			else if (BEARISH.contains(w)) {
				bear++;
			}
		}
		if (bull > bear) {
			return SentimentLabel.BULLISH;
		}
		if (bear > bull) {
			return SentimentLabel.BEARISH;
		}
		return SentimentLabel.NEUTRAL;
	}

	/** Map a label to a score: bullish +1, bearish -1, neutral 0. */
	static BigDecimal score(SentimentLabel label) {
		return switch (label) {
			case BULLISH -> BigDecimal.ONE;
			case BEARISH -> BigDecimal.valueOf(-1);
			case NEUTRAL -> BigDecimal.ZERO;
		};
	}
}
