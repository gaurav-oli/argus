package com.argus.filings;

import java.util.List;
import java.util.Locale;

/**
 * Turns a filing's <em>extracted fields</em> into a score, by fixed rules — the model reads the filing, code decides what it
 * is worth. (The README's rule: no number that drives a decision comes from an LLM.)
 */
public final class FilingScorer {

	private FilingScorer() {
	}

	/** Extracted, verified fields the scorer needs. */
	public record Fields(String guidance, String tone, boolean goingConcern, int positives, int concerns, int newRisks, boolean earningsRelease) {
	}

	/** -1 (very negative) .. +1 (very positive). */
	public static double score(Fields f) {
		if (f.goingConcern()) {
			return -1.0; // an explicit going-concern doubt overrides everything else
		}
		double s = 0;
		if (f.earningsRelease()) {
			s += switch (upper(f.guidance())) {
				case "RAISED" -> 0.5;
				case "LOWERED" -> -0.6;
				case "MAINTAINED" -> 0.1;
				default -> 0.0;
			};
		}
		s += switch (upper(f.tone())) {
			case "POSITIVE" -> 0.25;
			case "NEGATIVE" -> -0.3;
			default -> 0.0;
		};
		s += Math.max(-0.15, Math.min(0.15, 0.05 * (f.positives() - f.concerns())));
		if (f.newRisks() > 2) {
			s -= 0.1;
		}
		return Math.max(-1.0, Math.min(1.0, s));
	}

	/** Age-weighted combination of a ticker's recent digests: an earnings release counts most, and everything fades with age. */
	public static double combined(List<Aged> digests) {
		double num = 0, den = 0;
		for (Aged d : digests) {
			double kindWeight = d.earnings() ? 0.7 : 0.3;
			double window = d.earnings() ? 60 : 90;
			double freshness = Math.max(0, 1 - d.ageDays() / window);
			den += kindWeight;
			num += kindWeight * freshness * d.score();
		}
		return den == 0 ? 0 : Math.max(-1.0, Math.min(1.0, num / den));
	}

	public record Aged(double score, boolean earnings, long ageDays) {
	}

	private static String upper(String s) {
		return s == null ? "" : s.trim().toUpperCase(Locale.ROOT);
	}
}
