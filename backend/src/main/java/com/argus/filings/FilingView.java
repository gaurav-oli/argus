package com.argus.filings;

import java.time.LocalDate;
import java.util.List;

/**
 * What the rest of Argus needs to know about a ticker's recent filings — from stored digests only (no network, no model call).
 *
 * @param score    -1..+1, age-weighted combination ({@link FilingScorer#combined})
 * @param guidance the newest earnings release's guidance direction (RAISED / MAINTAINED / LOWERED / NONE), or null
 */
public record FilingView(String ticker, double score, String guidance, String tone, LocalDate latestEarningsDate, String headline,
		long ageDays, List<Item> digests) {

	/** One digest in the list. */
	public record Item(String form, String kind, LocalDate filedAt, String summary, String guidance, String guidanceDetail, String tone, double score,
			int verifiedFacts, int droppedFacts, String accession) {
	}
}
