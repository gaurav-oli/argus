package com.argus.internet;

import com.argus.intelligence.SentimentLabel;
import java.time.Instant;

/** A web mention as returned by an {@link InternetSource}, before persistence (Agent 3). */
public record RawWebMention(String ticker, String source, String externalId, String title, String url,
		long score, SentimentLabel sentiment, Instant postedAt) {
}
