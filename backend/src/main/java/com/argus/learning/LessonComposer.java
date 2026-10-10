package com.argus.learning;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * S-B3: turns a closed paper trade into a short, structured lesson — pure, no I/O and no model call, so it
 * is free, never fails a close, and every sentence is testable. The words come only from what Argus stored
 * (the recommendation, its signals, the trade and the Analyst's existing post-mortem); nothing is invented.
 *
 * <p>{@link #resolveChange} decides "what changed after this trade" from the nightly jobs that ran since it
 * closed: an adopted logic review (weights), an Agent 13 rule activated or retired, or an explicit
 * {@code NO_CHANGE} once both have run and changed nothing. Until then the answer is honestly {@code PENDING}.
 */
public final class LessonComposer {

	private LessonComposer() {
	}

	/** The closed trade, as stored. */
	public record TradeFacts(String ticker, String direction, boolean won, BigDecimal returnPct,
			BigDecimal excessReturnPct, String exitReason, Instant entryAt, Instant closedAt, String review) {
	}

	/** One agent signal on the recommendation that opened the trade. */
	public record Signal(String agent, String direction, BigDecimal signedWeight) {
	}

	/** The recommendation that opened the trade; null fields when it's gone or predates them. */
	public record CallFacts(String actionLabel, Integer conviction, BigDecimal bullProbability, String thesis,
			List<Signal> signals) {
	}

	public record Composed(String whyEntered, String outcome, String lesson, List<String> reliedOn) {
	}

	/** A logic-review run since the trade closed. */
	public record Review(Instant ranAt, boolean adopted, String reason, List<Proposal> proposals) {
	}

	public record Proposal(String agent, double factor, String why) {
	}

	/** An Agent 13 rule that was activated or retired since the trade closed. */
	public record RuleChange(Instant at, boolean activated, String description) {
	}

	public record Change(String kind, String summary) {
		public boolean pending() {
			return "PENDING".equals(kind);
		}
	}

	/** After this long with no review run at all, say so instead of waiting forever (e.g. jobs disabled). */
	static final Duration GIVE_UP_WAITING = Duration.ofDays(7);
	private static final int TOP_SIGNALS = 3;
	private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("MMM d", Locale.ENGLISH)
			.withZone(ZoneId.of("America/Toronto"));

	private static final Map<String, String> AGENT_NAMES = Map.ofEntries(
			Map.entry("agent-1-news", "News"), Map.entry("agent-2-social", "Social"),
			Map.entry("agent-3-internet", "Internet"), Map.entry("agent-4-financial", "SEC insider"),
			Map.entry("agent-7-calendar", "Calendar"), Map.entry("agent-8-macro", "Macro"),
			Map.entry("agent-10-technical", "Chart Reader"), Map.entry("agent-11-cause", "Cause of move"),
			Map.entry("agent-11-deep", "Deep Analyst"), Map.entry("agent-12-fundamental", "Fundamentals"),
			Map.entry("agent-14-filings", "Filings Reader"), Map.entry("agent-15-academic", "Academic Strategies"));

	/** A friendly name for an agent id; unknown ids are tidied rather than dropped. */
	public static String agentName(String id) {
		if (id == null) {
			return "an agent";
		}
		String known = AGENT_NAMES.get(id);
		if (known != null) {
			return known;
		}
		String tidy = id.replaceFirst("^agent-\\d+-", "").replace('-', ' ');
		return tidy.isEmpty() ? id : Character.toUpperCase(tidy.charAt(0)) + tidy.substring(1);
	}

	public static Composed compose(TradeFacts t, CallFacts call) {
		boolean bullish = "BULLISH".equalsIgnoreCase(t.direction());
		String side = bullish ? "long" : "short";

		// Why entered: the call, its numbers, and the signals that pushed hardest in the trade's direction.
		List<Signal> drivers = call == null || call.signals() == null ? List.of() : call.signals().stream()
				.filter(s -> s.signedWeight() != null && (bullish ? s.signedWeight().signum() > 0 : s.signedWeight().signum() < 0))
				.sorted(Comparator.comparing((Signal s) -> s.signedWeight().abs()).reversed())
				.limit(TOP_SIGNALS)
				.toList();
		StringBuilder why = new StringBuilder();
		why.append("Went ").append(side).append(' ').append(t.ticker());
		if (call != null && call.actionLabel() != null) {
			why.append(" on a ").append(call.actionLabel().toLowerCase(Locale.ROOT)).append(" call");
		}
		List<String> numbers = new ArrayList<>();
		if (call != null && call.conviction() != null) {
			numbers.add("conviction " + call.conviction());
		}
		if (call != null && call.bullProbability() != null) {
			int p = call.bullProbability().multiply(BigDecimal.valueOf(100)).setScale(0, RoundingMode.HALF_UP).intValue();
			numbers.add((bullish ? p : 100 - p) + "% odds on its side");
		}
		if (!numbers.isEmpty()) {
			why.append(" (").append(String.join(", ", numbers)).append(')');
		}
		why.append('.');
		if (!drivers.isEmpty()) {
			why.append(" Driven by ").append(String.join(", ", drivers.stream().map(s -> agentName(s.agent())).toList())).append('.');
		}
		String thesis = firstSentence(call == null ? null : call.thesis());
		if (thesis != null) {
			why.append(" Thesis: ").append(thesis);
		}
		if (call == null) {
			why.append(" (The recommendation behind it is no longer stored.)");
		}

		// Outcome: result, vs SPY, how it ended and how long it was held.
		StringBuilder out = new StringBuilder(t.won() ? "Won" : "Lost");
		if (t.returnPct() != null) {
			out.append(' ').append(signed(t.returnPct())).append('%');
		}
		if (t.excessReturnPct() != null) {
			out.append(" (").append(signed(t.excessReturnPct())).append(" pts vs SPY)");
		}
		out.append(" — ").append(exitPhrase(t.exitReason()));
		if (t.entryAt() != null && t.closedAt() != null) {
			long days = Math.max(0, Duration.between(t.entryAt(), t.closedAt()).toDays());
			out.append(" after ").append(days).append(days == 1 ? " day" : " days");
		}
		out.append('.');

		// Lesson: the Analyst's grounded post-mortem on a loss; on a win, which signals were right.
		String lesson;
		if (!t.won()) {
			lesson = t.review() != null && !t.review().isBlank()
					? t.review().trim()
					: "No post-mortem was written for this loss.";
		} else if (!drivers.isEmpty()) {
			lesson = "The thesis held: " + String.join(", ", drivers.stream().map(s -> agentName(s.agent())).toList())
					+ (drivers.size() == 1 ? " was" : " were") + " right on direction.";
		} else {
			lesson = "The call worked, but no individual signal is recorded for it.";
		}

		List<String> reliedOn = drivers.stream().map(Signal::agent).toList();
		return new Composed(why.toString(), out.toString(), lesson, reliedOn);
	}

	/**
	 * What changed after the trade closed. Weight changes come first (they touch the agents directly), then
	 * Agent 13 rules; {@code NO_CHANGE} needs both a logic review and a learner run since the close, because
	 * only then is "nothing changed" actually known. With neither, it stays {@code PENDING} — for at most
	 * {@link #GIVE_UP_WAITING}, after which it says plainly that no review has run.
	 */
	public static Change resolveChange(Instant closedAt, List<String> reliedOn, List<Review> reviewsSince,
			List<RuleChange> rulesSince, boolean learnerRanSince, Instant now) {
		Set<String> relied = Set.copyOf(reliedOn == null ? List.of() : reliedOn);
		List<String> parts = new ArrayList<>();
		String kind = null;

		Review adopted = reviewsSince.stream().filter(Review::adopted).findFirst().orElse(null);
		if (adopted != null) {
			kind = "WEIGHTS_ADJUSTED";
			List<String> moves = adopted.proposals().stream()
					.map(p -> agentName(p.agent()) + " ×" + String.format(Locale.ROOT, "%.2f", p.factor())
							+ (relied.contains(p.agent()) ? " (this trade relied on it)" : ""))
					.toList();
			parts.add("Logic review on " + DAY.format(adopted.ranAt()) + " adjusted weights"
					+ (moves.isEmpty() ? "" : ": " + String.join(" · ", moves)) + ".");
		}
		List<RuleChange> on = rulesSince.stream().filter(RuleChange::activated).toList();
		List<RuleChange> off = rulesSince.stream().filter(r -> !r.activated()).toList();
		if (!on.isEmpty()) {
			kind = kind == null ? "RULE_ACTIVATED" : kind;
			parts.add("Agent 13 activated " + ruleList(on) + ".");
		}
		if (!off.isEmpty()) {
			kind = kind == null ? "RULE_RETIRED" : kind;
			parts.add("Agent 13 retired " + ruleList(off) + ".");
		}
		if (kind != null) {
			return new Change(kind, String.join(" ", parts));
		}

		Review latest = reviewsSince.isEmpty() ? null : reviewsSince.get(0);
		if (latest != null && learnerRanSince) {
			String reason = latest.reason() == null || latest.reason().isBlank() ? "" : " (" + latest.reason().trim() + ")";
			return new Change("NO_CHANGE", "No change: the logic review on " + DAY.format(latest.ranAt())
					+ " kept the weights" + reason + ", and Agent 13 changed no rules.");
		}
		if (closedAt != null && now != null && Duration.between(closedAt, now).compareTo(GIVE_UP_WAITING) > 0) {
			String missing = latest == null && !learnerRanSince ? "neither the logic review nor Agent 13 has run"
					: latest == null ? "the logic review hasn't run" : "Agent 13 hasn't run";
			return new Change("NO_CHANGE", "No change recorded: " + missing + " since this trade closed.");
		}
		return new Change("PENDING", "Waiting for tonight's logic review and Agent 13 run.");
	}

	private static String ruleList(List<RuleChange> rules) {
		List<String> quoted = rules.stream().limit(2).map(r -> "“" + r.description() + "”").toList();
		int more = rules.size() - quoted.size();
		return String.join(" and ", quoted) + (more > 0 ? " (+" + more + " more)" : "");
	}

	static String exitPhrase(String reason) {
		if (reason == null) {
			return "closed";
		}
		return switch (reason) {
			case "HORIZON" -> "held to its horizon";
			case "STOP" -> "the stop was hit";
			case "TRAILING_STOP" -> "the trailing stop was hit";
			case "THESIS_FLIP" -> "closed early when Agent 11 turned against it";
			case "THESIS_DECAY" -> "closed early when a fresh call pointed the other way";
			case "TAKE_PROFIT" -> "half taken as profit";
			default -> "closed (" + reason.toLowerCase(Locale.ROOT).replace('_', ' ') + ")";
		};
	}

	private static String firstSentence(String text) {
		if (text == null || text.isBlank()) {
			return null;
		}
		String t = text.trim().replaceAll("\\s+", " ");
		int end = t.indexOf(". ");
		String s = end > 0 ? t.substring(0, end + 1) : t;
		if (s.length() > 220) {
			s = s.substring(0, 217).trim() + "…";
		}
		return s.endsWith(".") || s.endsWith("…") || s.endsWith("!") || s.endsWith("?") ? s : s + ".";
	}

	private static String signed(BigDecimal v) {
		BigDecimal r = v.setScale(1, RoundingMode.HALF_UP);
		return (r.signum() > 0 ? "+" : "") + r.toPlainString();
	}
}
