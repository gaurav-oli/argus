package com.argus.social;

import com.argus.intelligence.SentimentLabel;
import java.time.Instant;

/**
 * A raw crowd post as returned by a {@link SocialSource}, before persistence (Agent 2).
 * {@code sentimentHint} is the source's own crowd tag (StockTwits Bullish/Bearish) when present,
 * else {@code null} — the ingestion service classifies the rest.
 */
public record RawSocialPost(String source, String externalId, String ticker, String author,
		String body, String url, Instant postedAt, SentimentLabel sentimentHint) {
}
