package com.argus.learning;

import java.math.BigDecimal;
import java.util.List;

/**
 * S-B4 — what similar past paper trades advise for a new entry.
 *
 * @param action          PROCEED, SIZE_DOWN, TIGHTEN_STOP, SKIP, or NO_PATTERN (too few matches; proceed unchanged)
 * @param sizeMultiplier  applied to the position size (0 on SKIP, 1.0 = unchanged)
 * @param stopKeep        share of the stop distance to keep (1.0 = unchanged, 0.7 = tightened by 30%)
 * @param pattern         what the matches share with this setup, e.g. "lead=NEWS · regime=RISK_OFF"; null without matches
 * @param note            the logged decision, e.g. "Matched 9 similar setups [...] → tightened stop."
 */
public record PatternAdvice(String action, double sizeMultiplier, double stopKeep, int matches, int wins, Integer winRatePct,
		BigDecimal avgReturnPct, Integer stopOutPct, String pattern, String note, List<Long> similarTradeIds) {

	public static PatternAdvice noPattern(int matches, String note) {
		return new PatternAdvice("NO_PATTERN", 1.0, 1.0, matches, 0, null, null, null, null, note, List.of());
	}

	public boolean skip() {
		return "SKIP".equals(action);
	}
}
