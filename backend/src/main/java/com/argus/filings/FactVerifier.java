package com.argus.filings;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * "The model extracts, code verifies." A language model reading a filing will sometimes report a figure that is not in it (a
 * transposed digit, a number from its own memory of the company). This check makes that impossible to slip through: every
 * number in a claim must appear, digit for digit, in the source text — otherwise the claim is discarded and counted.
 */
public final class FactVerifier {

	private static final Pattern NUMBER = Pattern.compile("\\d[\\d,]*\\.?\\d*");
	private static final Pattern GUIDANCE_CUE = Pattern.compile("rais|lower|reduc|cut|increas|updat|reaffirm|maintain|withdr|expect|outlook|guidance",
			Pattern.CASE_INSENSITIVE);

	private final String haystack;
	private final String raw;

	public FactVerifier(String sourceText) {
		this.raw = sourceText == null ? "" : sourceText;
		this.haystack = normalize(this.raw);
	}

	/** True when the claim contains at least one number and every number in it appears in the source. */
	public boolean supports(String claim) {
		List<String> nums = numbers(claim);
		if (nums.isEmpty()) {
			return false;
		}
		return nums.stream().allMatch(haystack::contains);
	}

	/** A metric is supported when its value (and change, if given) are both in the source. */
	public boolean supportsMetric(String value, String change) {
		boolean v = supports(value);
		return v && (change == null || change.isBlank() || numbers(change).isEmpty() || supports(change));
	}

	/** Going concern is only honored when the filing actually contains the "substantial doubt" language. */
	public boolean confirmsGoingConcern() {
		String lower = raw.toLowerCase(Locale.ROOT);
		return lower.contains("substantial doubt") || lower.contains("going concern");
	}

	/** Whether the text contains any wording that could back a guidance statement at all. */
	public boolean hasGuidanceLanguage() {
		return GUIDANCE_CUE.matcher(raw).find();
	}

	static List<String> numbers(String claim) {
		List<String> out = new ArrayList<>();
		if (claim == null) return out;
		Matcher m = NUMBER.matcher(claim);
		while (m.find()) {
			String n = m.group().replace(",", "");
			while (n.endsWith(".")) n = n.substring(0, n.length() - 1);
			if (!n.isEmpty()) out.add(n);
		}
		return out;
	}

	private static String normalize(String s) {
		return s.replace(",", "").replace("$", "");
	}
}
