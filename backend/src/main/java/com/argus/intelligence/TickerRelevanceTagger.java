package com.argus.intelligence;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Tags an article with the held tickers it is relevant to (Story 4.1, FR-8). Per-ticker sources
 * (Finnhub) already carry their query ticker; broad sources (GDELT/RSS) are matched by scanning the
 * headline + summary for a held symbol as a whole word (case-insensitive, e.g. {@code AAPL} but not
 * inside "AAPLE"). This is deliberately simple keyword relevance — sentiment and richer scoring are
 * Story 4.2's job.
 */
@Component
public class TickerRelevanceTagger {

	// Compiled whole-word matchers, cached per ticker — tag() runs on every ingested article, so
	// recompiling a regex per (article × held ticker) would be needless quadratic work on the hot path.
	private final Map<String, Pattern> patternCache = new ConcurrentHashMap<>();

	/** Tag {@code article} against the held universe; returns the matched symbols (possibly empty). */
	public List<String> tag(RawArticle article, Set<String> heldTickers) {
		Set<String> matched = new LinkedHashSet<>();
		// Trust the source's own per-ticker scoping first (only for symbols we actually hold).
		for (String t : article.queryTickers()) {
			String norm = normalize(t);
			if (heldTickers.contains(norm)) {
				matched.add(norm);
			}
		}
		String haystack = (safe(article.headline()) + " " + safe(article.summary()));
		for (String held : heldTickers) {
			if (matched.contains(held)) {
				continue;
			}
			if (mentions(haystack, held)) {
				matched.add(held);
			}
		}
		return List.copyOf(matched);
	}

	private boolean mentions(String haystack, String ticker) {
		// Whole-word, case-insensitive match so "AI" doesn't hit "again" and "AAPL" doesn't hit "AAPLE".
		return patternFor(ticker).matcher(haystack).find();
	}

	private Pattern patternFor(String ticker) {
		return patternCache.computeIfAbsent(ticker,
				t -> Pattern.compile("\\b" + Pattern.quote(t) + "\\b", Pattern.CASE_INSENSITIVE));
	}

	private static String normalize(String ticker) {
		return ticker == null ? "" : ticker.trim().toUpperCase();
	}

	private static String safe(String s) {
		return s == null ? "" : s;
	}
}
