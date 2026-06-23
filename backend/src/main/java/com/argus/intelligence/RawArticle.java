package com.argus.intelligence;

import java.time.Instant;

/**
 * A raw article as returned by a {@link NewsSource}, before relevance tagging or persistence
 * (Story 4.1). {@code externalId} is the source's stable identifier for the item (used for
 * dedup); {@code queryTickers} are the symbols this item was fetched for (empty for broad feeds
 * like GDELT/RSS, where relevance is resolved by the tagger).
 */
public record RawArticle(
		String source,
		String externalId,
		String url,
		String headline,
		String summary,
		Instant publishedAt,
		java.util.List<String> queryTickers) {
}
