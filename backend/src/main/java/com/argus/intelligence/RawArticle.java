package com.argus.intelligence;

import java.time.Instant;

/**
 * A raw article as returned by a {@link NewsSource}, before relevance tagging or persistence
 * (Story 4.1). {@code externalId} is the source's stable identifier for the item (used for
 * dedup); {@code queryTickers} are the symbols this item was fetched for (empty for broad feeds
 * like GDELT/RSS, where relevance is resolved by the tagger). {@code relatedTickers} are any
 * additional symbols the source itself associates with the item (e.g. Finnhub's {@code related}
 * field on company-news, which can name other tickers in a shared story) — unfiltered, may include
 * symbols outside the held universe; used by {@link StrangerDangerService} (Epic 4 follow-up) to
 * widen stranger-ticker recall beyond {@code $cashtag} mentions. Empty for sources that don't
 * report it.
 */
public record RawArticle(
		String source,
		String externalId,
		String url,
		String headline,
		String summary,
		Instant publishedAt,
		java.util.List<String> queryTickers,
		java.util.List<String> relatedTickers) {

	public RawArticle(String source, String externalId, String url, String headline, String summary,
			Instant publishedAt, java.util.List<String> queryTickers) {
		this(source, externalId, url, headline, summary, publishedAt, queryTickers, java.util.List.of());
	}
}
