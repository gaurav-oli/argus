package com.argus.intelligence;

import java.util.Collection;
import java.util.List;

/**
 * A pluggable upstream news provider (Finnhub, GDELT, RSS) for Agent 1 (Story 4.1). Implementations
 * are key-gated where required (Finnhub) and absent otherwise, so the platform runs with whatever
 * subset is configured. Fetch failures must be swallowed and surfaced as an empty list — a single
 * flaky source never breaks the ingestion cycle.
 */
public interface NewsSource {

	/** Stable source name; also the {@code source} column value and dedup namespace. */
	String name();

	/**
	 * Fetch recent articles. {@code heldTickers} lets per-ticker sources (Finnhub company-news)
	 * scope their queries; broad sources (GDELT/RSS) ignore it and return everything, leaving
	 * relevance to the tagger. Never throws — returns an empty list on failure.
	 */
	List<RawArticle> fetch(Collection<String> heldTickers);
}
