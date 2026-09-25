package com.argus.learning;

import com.argus.regime.Sector;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

/** Turns feature tokens into plain English, so every learned rule can be read (and doubted) by a person. */
public final class RuleText {

	private RuleText() {
	}

	/** One token as a phrase, e.g. {@code lead=SOCIAL} → "led by crowd sentiment". */
	public static String phrase(String token) {
		int i = token.indexOf('=');
		String key = i < 0 ? token : token.substring(0, i);
		String v = i < 0 ? "" : token.substring(i + 1);
		return switch (key) {
			case "dir" -> "BULLISH".equals(v) ? "bullish calls" : "bearish calls";
			case "sector" -> "in " + sectorLabel(v);
			case "has" -> "backed by " + groupLabel(v);
			case "lead" -> "led by " + groupLabel(v);
			case "regime" -> "in a " + v.toLowerCase(Locale.ROOT).replace('_', '-') + " market";
			case "trend" -> "with the chart in a" + ("UPTREND".equals(v) ? "n uptrend" : "DOWNTREND".equals(v) ? " downtrend" : " sideways trend");
			case "chart" -> "with a " + v.toLowerCase(Locale.ROOT) + " chart read";
			case "deep" -> "NONE".equals(v) ? "with no deep analysis" : "when Agent 11 said " + v.toLowerCase(Locale.ROOT).replace('_', ' ');
			case "hold" -> "held about " + v + " days";
			case "conv" -> "at conviction " + v;
			case "vol" -> "in " + v + "-volatility stocks";
			case "price" -> "under10".equals(v) ? "in stocks under $10" : "in stocks priced " + v.replace("-", " to $").replace("+", "+");
			case "earnings" -> "just before earnings";
			case "ticker" -> "on " + v;
			default -> token;
		};
	}

	/** "Bullish calls in Autos & EV led by crowd sentiment". */
	public static String subject(Set<String> predicates) {
		List<String> parts = new ArrayList<>();
		String first = null;
		for (String t : new TreeSet<>(predicates)) {
			if (t.startsWith("dir=")) {
				first = phrase(t);
			}
			else {
				parts.add(phrase(t));
			}
		}
		String head = first == null ? "Calls" : first.substring(0, 1).toUpperCase(Locale.ROOT) + first.substring(1);
		return parts.isEmpty() ? head : head + " " + String.join(" ", parts);
	}

	/** The full sentence for a rule, built only from the measured numbers. */
	public static String describe(Set<String> predicates, double meanExcess, int clusters, double winRate) {
		return String.format(Locale.ROOT, "%s %s the S&P 500 by an average of %.1f%% over %d independent bets (win rate %.0f%%).",
				subject(predicates), meanExcess < 0 ? "underperformed" : "outperformed", Math.abs(meanExcess), clusters, winRate * 100);
	}

	private static String sectorLabel(String name) {
		try {
			return Sector.valueOf(name).label();
		}
		catch (IllegalArgumentException ex) {
			return name;
		}
	}

	private static String groupLabel(String name) {
		try {
			return SignalGroup.valueOf(name).label();
		}
		catch (IllegalArgumentException ex) {
			return name;
		}
	}
}
