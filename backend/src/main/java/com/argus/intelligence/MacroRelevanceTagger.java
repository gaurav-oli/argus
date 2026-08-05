package com.argus.intelligence;

import java.util.List;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Flags articles covering broad, market-moving macro/political news — tariffs, Fed policy,
 * executive orders — that {@link TickerRelevanceTagger} would otherwise drop entirely: "Trump
 * announces new tariffs on Chinese imports" names no stock ticker, so it was being ingested,
 * tagged with zero tickers, and never read back by anything, even though this kind of story moves
 * every held position at once. Matched articles get tagged with {@link #MACRO_TAG} — a plain
 * string in the same {@code tickers[]} array real symbols use, so the existing per-ticker query
 * machinery (e.g. {@code NewsArticleRepository.findAnalyzedForTicker}) picks it up for free by
 * just passing {@code "MACRO"} instead of a real symbol.
 *
 * <p>Deliberately simple whole-phrase, case-insensitive keyword matching — same philosophy as
 * {@link TickerRelevanceTagger}, not NLP/NER. Keywords are durable macro/policy terms, not bare
 * words like "fed" that would false-positive on ordinary text ("fed up with...").
 *
 * <p>Also the single source of truth for macro/policy keywords in {@link BreakingNewsAlertService},
 * which used to keep its own separate list — the two had already silently drifted apart (this one
 * had no currency/FX terms either, until a real yen-intervention story was missed by both paths at
 * once). {@code BreakingNewsAlertService} still keeps its own list for topics that are breaking-news
 * worthy but not macro/policy per se (war, a market crash, a bankruptcy).
 */
@Component
public class MacroRelevanceTagger {

	/** Pseudo-ticker tag for macro-relevant articles — reuses the existing tickers[] column/query. */
	public static final String MACRO_TAG = "MACRO";

	private static final List<String> KEYWORDS = List.of(
			"trump", "tariff", "tariffs", "trade war", "federal reserve", "fomc",
			"rate cut", "rate hike", "interest rate decision", "white house",
			"executive order", "government shutdown", "sanction", "sanctions",
			// Currency/FX policy — a distinct blind spot found in production (a yen-intervention story
			// moved markets and was never surfaced anywhere, ticker signal or breaking alert, because
			// neither keyword list had any currency terms at all).
			"yen", "dollar", "euro", "currency", "forex", "devaluation", "currency intervention",
			"bank of japan", "boj", "ecb", "pboc");

	private static final Pattern PATTERN = Pattern.compile(
			"\\b(" + String.join("|", KEYWORDS.stream().map(Pattern::quote).toList()) + ")\\b",
			Pattern.CASE_INSENSITIVE);

	/** Whether this headline/summary covers macro/political market-moving news. */
	public boolean isMacroRelevant(String headline, String summary) {
		String haystack = safe(headline) + " " + safe(summary);
		return PATTERN.matcher(haystack).find();
	}

	private static String safe(String s) {
		return s == null ? "" : s;
	}
}
