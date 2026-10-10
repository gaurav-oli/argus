package com.argus.learning;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * S-B4 — given a new setup's fingerprint and the closed paper trades the library knows, find the similar ones
 * and say what their outcomes advise. Pure: no I/O, no model calls.
 *
 * <p>Similar = same direction and a Jaccard overlap of at least {@value #MIN_SIMILARITY} between the two token
 * sets (horizon tokens ignored, since every leg of one call shares the rest). Advice, from the matches:
 * <ul>
 * <li>fewer than {@value #MIN_MATCHES} matches → {@code NO_PATTERN}: proceed unchanged (the library fails open);</li>
 * <li>at least {@value #SKIP_MIN_MATCHES} matches and a win rate at or under {@value #SKIP_MAX_WIN_RATE}% →
 * {@code SKIP};</li>
 * <li>a win rate under {@value #SIZE_DOWN_BELOW_WIN_RATE}% → {@code SIZE_DOWN} to ×{@value #SIZE_DOWN_MULTIPLIER};</li>
 * <li>at least {@value #TIGHTEN_MIN_STOP_OUT_PCT}% of the matches stopped out → {@code TIGHTEN_STOP}: keep
 * {@value #TIGHTEN_KEEP} of the stop distance (both can apply; the action names the stronger one);</li>
 * <li>otherwise {@code PROCEED}.</li>
 * </ul>
 */
public final class PatternMatcher {

	static final double MIN_SIMILARITY = 0.5;
	static final int MIN_MATCHES = 5;
	static final int SKIP_MIN_MATCHES = 8;
	static final int SKIP_MAX_WIN_RATE = 25;
	static final int SIZE_DOWN_BELOW_WIN_RATE = 45;
	static final double SIZE_DOWN_MULTIPLIER = 0.5;
	static final int TIGHTEN_MIN_STOP_OUT_PCT = 50;
	static final double TIGHTEN_KEEP = 0.7;
	/** A token shared by at least this share of the matches is part of the named pattern. */
	static final double PATTERN_CORE_SHARE = 0.8;
	static final int PATTERN_MAX_TOKENS = 4;
	/** The library only looks at this many of the most similar matches. */
	static final int MAX_MATCHES = 50;

	private PatternMatcher() {
	}

	/** One closed paper trade the library can compare against. */
	public record PastTrade(long tradeId, Set<String> fingerprint, boolean won, BigDecimal returnPct, String exitReason) {
	}

	public static PatternAdvice advise(Set<String> fingerprint, List<PastTrade> library) {
		Set<String> mine = comparable(fingerprint);
		if (mine.isEmpty()) {
			return PatternAdvice.noPattern(0, "No prior pattern — this setup has no fingerprint to compare.");
		}
		record Scored(PastTrade t, double sim) {
		}
		List<Scored> scored = new ArrayList<>();
		for (PastTrade t : library) {
			double sim = jaccard(mine, comparable(t.fingerprint()));
			if (sim >= MIN_SIMILARITY) scored.add(new Scored(t, sim));
		}
		scored.sort(Comparator.comparingDouble(Scored::sim).reversed().thenComparing(s -> -s.t().tradeId()));
		List<PastTrade> matches = scored.stream().limit(MAX_MATCHES).map(Scored::t).toList();
		int n = matches.size();
		if (n < MIN_MATCHES) {
			return PatternAdvice.noPattern(n, "No prior pattern — %d similar closed trade%s (needs %d) — proceeding as planned."
					.formatted(n, n == 1 ? "" : "s", MIN_MATCHES));
		}
		int wins = (int) matches.stream().filter(PastTrade::won).count();
		int winRate = Math.round(100f * wins / n);
		int stopOuts = (int) matches.stream().filter(t -> t.exitReason() != null && t.exitReason().contains("STOP")).count();
		int stopOutPct = Math.round(100f * stopOuts / n);
		List<BigDecimal> returns = matches.stream().map(PastTrade::returnPct).filter(java.util.Objects::nonNull).toList();
		BigDecimal avg = returns.isEmpty() ? null
				: returns.stream().reduce(BigDecimal.ZERO, BigDecimal::add)
						.divide(BigDecimal.valueOf(returns.size()), 4, RoundingMode.HALF_UP);
		String pattern = patternName(mine, matches);
		String stats = "%d similar setups [%s]: %d won (%d%%)%s, %d%% stopped out"
				.formatted(n, pattern, wins, winRate, avg == null ? "" : ", avg " + signed(avg) + "%", stopOutPct);
		List<Long> ids = matches.stream().map(PastTrade::tradeId).toList();

		if (n >= SKIP_MIN_MATCHES && winRate <= SKIP_MAX_WIN_RATE) {
			return new PatternAdvice("SKIP", 0, 1.0, n, wins, winRate, avg, stopOutPct, pattern,
					"Matched " + stats + " → skipped: this setup has lost too often.", ids);
		}
		boolean sizeDown = winRate < SIZE_DOWN_BELOW_WIN_RATE;
		boolean tighten = stopOutPct >= TIGHTEN_MIN_STOP_OUT_PCT;
		double size = sizeDown ? SIZE_DOWN_MULTIPLIER : 1.0;
		double keep = tighten ? TIGHTEN_KEEP : 1.0;
		String action = sizeDown ? "SIZE_DOWN" : tighten ? "TIGHTEN_STOP" : "PROCEED";
		List<String> did = new ArrayList<>();
		if (sizeDown) did.add("half size");
		if (tighten) did.add("tightened stop");
		String outcome = did.isEmpty() ? "proceeded as planned" : String.join(" + ", did);
		return new PatternAdvice(action, size, keep, n, wins, winRate, avg, stopOutPct, pattern,
				"Matched " + stats + " → " + outcome + ".", ids);
	}

	/** The tokens two setups are compared on: everything except the horizon. */
	static Set<String> comparable(Set<String> tokens) {
		return tokens == null ? Set.of()
				: tokens.stream().filter(t -> !t.startsWith("hold=")).collect(Collectors.toUnmodifiableSet());
	}

	static double jaccard(Set<String> a, Set<String> b) {
		if (a.isEmpty() || b.isEmpty()) return 0;
		long inter = a.stream().filter(b::contains).count();
		return (double) inter / (a.size() + b.size() - inter);
	}

	/**
	 * A readable name for what the matches have in common with this setup: the tokens most of them share
	 * (direction left out, since every match shares it), e.g. "lead=NEWS · regime=RISK_OFF · trend=DOWNTREND".
	 */
	static String patternName(Set<String> mine, List<PastTrade> matches) {
		Map<String, Integer> counts = new HashMap<>();
		for (PastTrade t : matches) {
			for (String tok : comparable(t.fingerprint())) {
				if (mine.contains(tok) && !tok.startsWith("dir=")) counts.merge(tok, 1, Integer::sum);
			}
		}
		List<String> core = counts.entrySet().stream()
				.filter(e -> e.getValue() >= PATTERN_CORE_SHARE * matches.size())
				.sorted(Map.Entry.<String, Integer>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
				.limit(PATTERN_MAX_TOKENS).map(Map.Entry::getKey).sorted().toList();
		return core.isEmpty() ? "mixed setups" : String.join(" · ", core);
	}

	private static String signed(BigDecimal v) {
		BigDecimal r = v.setScale(1, RoundingMode.HALF_UP);
		return (r.signum() > 0 ? "+" : "") + r.toPlainString();
	}
}
