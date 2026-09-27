package com.argus.filings;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pulls the parts of an SEC filing worth reading out of its plain text: the guidance/outlook window of an earnings
 * press release, and the MD&amp;A and risk-factor sections of a 10-Q/10-K. Filings are long (a 10-Q is hundreds of
 * thousands of characters); the local model has a small context and a token cap, so we hand it only what matters.
 * Pure and deterministic.
 */
public final class FilingExtractor {

	private static final Pattern MDNA = Pattern.compile("management.{0,3}s discussion and analysis of financial condition and results of operations",
			Pattern.CASE_INSENSITIVE);
	private static final Pattern ITEM3 = Pattern.compile("item\\s*3\\.?\\s*[-–—:]?\\s*quantitative and qualitative", Pattern.CASE_INSENSITIVE);
	private static final Pattern ITEM1A = Pattern.compile("item\\s*1a\\.?\\s*[-–—:]?\\s*risk factors", Pattern.CASE_INSENSITIVE);
	private static final Pattern ITEM2 = Pattern.compile("item\\s*2\\.?\\s*[-–—:]?\\s*(unregistered|management|properties)", Pattern.CASE_INSENSITIVE);
	private static final Pattern OUTLOOK = Pattern.compile("\\b(outlook|guidance|expects? .{0,40}(revenue|net sales))", Pattern.CASE_INSENSITIVE);
	private static final int MIN_SECTION = 1500;

	private FilingExtractor() {
	}

	/**
	 * For an earnings press release: the opening (headline results, which come first by convention) plus the outlook /
	 * guidance window, within {@code max} characters overall.
	 */
	public static String pressRelease(String text, int max) {
		if (text == null || text.isBlank()) return "";
		int head = Math.min(text.length(), (int) (max * 0.6));
		String opening = text.substring(0, head);
		Matcher m = OUTLOOK.matcher(text);
		int from = -1;
		while (m.find()) {
			if (m.start() > head) { // an outlook mention beyond what the opening already covers
				from = m.start();
				break;
			}
		}
		if (from < 0) {
			return text.substring(0, Math.min(text.length(), max));
		}
		int room = max - head - 20;
		return opening + "\n[...]\n" + text.substring(from, Math.min(text.length(), from + Math.max(500, room)));
	}

	/** The MD&amp;A of a 10-Q/10-K: the longest span after a heading match (skipping the table-of-contents mention), or "". */
	public static String mdna(String text, int max) {
		return longestSection(text, MDNA, ITEM3, max);
	}

	/** The risk-factors section, or "" when absent or when it only says there were no material changes. */
	public static String riskFactors(String text, int max) {
		String section = longestSection(text, ITEM1A, ITEM2, max);
		if (section.isEmpty()) return "";
		String lead = section.substring(0, Math.min(section.length(), 700)).toLowerCase();
		if (lead.contains("no material changes") || lead.contains("not materially changed") || lead.contains("no material change")) return "";
		return section;
	}

	private static String longestSection(String text, Pattern start, Pattern end, int max) {
		if (text == null || text.isBlank()) return "";
		Matcher s = start.matcher(text);
		String best = "";
		while (s.find()) {
			Matcher e = end.matcher(text);
			int stop = text.length();
			if (e.find(s.end())) stop = e.start();
			String seg = text.substring(s.start(), stop);
			if (seg.length() > best.length()) best = seg;
		}
		if (best.length() < MIN_SECTION) return "";
		return best.length() <= max ? best : best.substring(0, max);
	}
}
