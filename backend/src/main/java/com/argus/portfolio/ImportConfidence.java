package com.argus.portfolio;

import java.math.BigDecimal;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Decides whether a parsed statement is trustworthy enough to apply automatically, or needs the
 * heavier adaptive LLM pipeline ({@link AdaptiveStatementParser}) instead. The same gate applies
 * regardless of WHY a parse might be weak — a bank we've never seen, a familiar bank that quietly
 * changed its layout, or the fast heuristic parser just not being built for this one — so there is
 * one rule for "good enough", not a separate "is this a known bank" special case to keep in sync.
 *
 * <p>Two independent signals, either of which is disqualifying:
 * <ul>
 *   <li>the parse itself is thin — nothing extracted, every row needs review, or the parser's own
 *       message says it couldn't read the statement;</li>
 *   <li>the statement states a total value somewhere and what was extracted doesn't add up to it
 *       (a real, if approximate, check that nothing material was silently dropped).</li>
 * </ul>
 * Pure — no I/O, unit-tested directly.
 */
public final class ImportConfidence {

	/** How far the extracted total may drift from a stated statement total before it's suspect.
	 * Generous on purpose — FX estimates, missing per-share prices, and rounding all land inside this
	 * without a real miss; it's a safety net for a genuinely wrong/incomplete read, not a precision check. */
	private static final BigDecimal TOLERANCE = new BigDecimal("0.15");

	/** Statements phrase their grand total many different ways; this is deliberately loose — a miss
	 * here just means that check is skipped, not that the parse is rejected. */
	private static final Pattern TOTAL_LINE = Pattern.compile(
			"(?i)total\\s+(?:portfolio\\s+|account\\s+|market\\s+)?(?:value|assets?)\\D{0,10}([\\d,]+\\.\\d{2})");

	private ImportConfidence() {
	}

	public record Verdict(boolean confident, String reason) {
		static Verdict ok() {
			return new Verdict(true, null);
		}

		static Verdict weak(String reason) {
			return new Verdict(false, reason);
		}
	}

	public static Verdict assess(StatementParser.ParseResult result, String statementText) {
		if (result.holdings().isEmpty()) {
			return Verdict.weak("no holdings were found");
		}
		if (result.message() != null && !result.message().isBlank()) {
			return Verdict.weak("the parser reported: " + result.message());
		}
		long flagged = result.holdings().stream().filter(ParsedHolding::needsReview).count();
		if (flagged > 0) {
			return Verdict.weak(flagged + " of " + result.holdings().size() + " holding(s) need review");
		}

		BigDecimal statedTotal = statedTotal(statementText);
		if (statedTotal != null) {
			BigDecimal extracted = extractedTotal(result);
			BigDecimal diff = extracted.subtract(statedTotal).abs();
			BigDecimal allowed = statedTotal.abs().multiply(TOLERANCE);
			if (diff.compareTo(allowed) > 0) {
				return Verdict.weak(String.format(java.util.Locale.ROOT,
						"extracted total %.2f doesn't match the statement's stated total %.2f", extracted, statedTotal));
			}
		}
		return Verdict.ok();
	}

	/** The first "Total ... Value: $X" style figure found in the statement text, or null. */
	static BigDecimal statedTotal(String statementText) {
		if (statementText == null) {
			return null;
		}
		Matcher m = TOTAL_LINE.matcher(statementText);
		if (!m.find()) {
			return null;
		}
		try {
			return new BigDecimal(m.group(1).replace(",", ""));
		}
		catch (NumberFormatException ex) {
			return null;
		}
	}

	/** Sum of every holding's book value plus every cash balance — same currency-blind approximation
	 * a statement's own grand total usually is (brokerages mix CAD/USD into one number too). */
	static BigDecimal extractedTotal(StatementParser.ParseResult result) {
		BigDecimal total = BigDecimal.ZERO;
		for (ParsedHolding h : result.holdings()) {
			if (h.costBasis() != null) {
				total = total.add(h.costBasis());
			}
		}
		for (ParsedCash c : result.cash()) {
			if (c.amount() != null) {
				total = total.add(c.amount());
			}
		}
		return total;
	}
}
