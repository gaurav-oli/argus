package com.argus.learning;

import java.util.Set;

/**
 * S-B4 — the paper Investor consults this before every new entry. Implementations must fail open: on any
 * error, return {@link PatternAdvice#noPattern} so the trade proceeds.
 */
public interface PatternLibrary {

	/** Look up similar past setups for this entry, log the check, and return the advice. */
	PatternAdvice consult(Long recommendationId, String ticker, String direction, Set<String> fingerprint);

	/** A no-op library (nothing known) — for tests and contexts where the library is off. */
	static PatternLibrary none() {
		return (recommendationId, ticker, direction, fingerprint) -> PatternAdvice.noPattern(0, "No prior pattern — library off.");
	}
}
