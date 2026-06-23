package com.argus.intelligence;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Extracts candidate stock symbols from free news text via {@code $CASHTAG} notation (Story 4.4).
 * Cashtags are used deliberately over bare uppercase words: precision matters here — a false
 * "stranger" would raise spurious pump-and-dump scrutiny. Recall is improved later when the Finnhub
 * market-data seam contributes its own related-ticker data.
 */
@Component
public class CashtagExtractor {

	// $ followed by 1–5 letters, not part of a longer alphanumeric token.
	private static final Pattern CASHTAG = Pattern.compile("\\$([A-Za-z]{1,5})(?![A-Za-z0-9])");

	/** Distinct upper-case symbols referenced as cashtags in {@code text} (empty if none). */
	public Set<String> extract(String text) {
		Set<String> out = new LinkedHashSet<>();
		if (text == null || text.isBlank()) {
			return out;
		}
		Matcher m = CASHTAG.matcher(text);
		while (m.find()) {
			out.add(m.group(1).toUpperCase());
		}
		return out;
	}
}
