package com.argus.learning;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The live set of learned rules, and the one implementation of {@link Lessons}. Reads only {@code ACTIVE}
 * rules (a rule must replicate on held-out trades before it is ever active) from a short cache, so the
 * recommender's hot path does not query the database per ticker.
 *
 * <p>Combination is deliberately bounded so that no pile-up of rules can distort a decision: the summed
 * score adjustment is clamped to [{@value #MIN_SCORE_DELTA}, +{@value #MAX_SCORE_DELTA}] (learning is far more willing to
 * warn than to encourage), and the size multiplier to [{@value #MIN_SIZE}, {@value #MAX_SIZE}].
 */
@Component
public class LessonBook implements Lessons {

	private static final Logger log = LoggerFactory.getLogger(LessonBook.class);
	static final int MIN_SCORE_DELTA = -25;
	static final int MAX_SCORE_DELTA = 10;
	static final double MIN_SIZE = 0.25;
	static final double MAX_SIZE = 1.5;
	private static final Duration TTL = Duration.ofMinutes(2);

	private final LearnedRuleRepository rules;
	private volatile List<LearnedRule> cache = List.of();
	private volatile Instant loadedAt = Instant.EPOCH;

	public LessonBook(LearnedRuleRepository rules) {
		this.rules = rules;
	}

	/** Force a reload — called by the learner after it activates or retires a rule. */
	public void reload() {
		try {
			cache = List.copyOf(rules.findByStatusOrderByActivatedAtDesc(LearnedRule.Status.ACTIVE));
			loadedAt = Instant.now();
		}
		catch (RuntimeException ex) {
			log.warn("Lesson book reload failed: {} — keeping the previous rules", ex.getMessage());
		}
	}

	private List<LearnedRule> active() {
		if (loadedAt.isBefore(Instant.now().minus(TTL))) {
			reload();
		}
		return cache;
	}

	@Override
	public LessonEffect evaluate(Set<String> tokens) {
		if (tokens == null || tokens.isEmpty()) {
			return LessonEffect.none();
		}
		int delta = 0;
		String block = null;
		Integer holdCap = null;
		double size = 1.0;
		List<LessonEffect.Applied> applied = new ArrayList<>();
		for (LearnedRule r : active()) {
			if (!r.matches(tokens)) {
				continue;
			}
			double v = r.getEffectValue().doubleValue();
			switch (r.getKind()) {
				case PENALTY -> delta -= (int) Math.round(v);
				case BOOST -> delta += (int) Math.round(v);
				case BLOCK -> {
					if (block == null) block = r.getDescription();
				}
				case CAP_HOLD -> holdCap = holdCap == null ? (int) Math.round(v) : Math.min(holdCap, (int) Math.round(v));
				case SIZE -> size *= v;
			}
			applied.add(new LessonEffect.Applied(r.getId(), r.getKind().name(), r.getDescription(), stats(r), v));
		}
		if (applied.isEmpty()) {
			return LessonEffect.none();
		}
		delta = Math.max(MIN_SCORE_DELTA, Math.min(MAX_SCORE_DELTA, delta));
		size = Math.max(MIN_SIZE, Math.min(MAX_SIZE, size));
		return new LessonEffect(delta, block, holdCap, size, List.copyOf(applied));
	}

	@Override
	public String promptSection(Set<String> tokens) {
		LessonEffect effect = evaluate(tokens);
		if (effect.applied().isEmpty()) {
			return "";
		}
		StringBuilder sb = new StringBuilder("LESSONS MEASURED FROM ARGUS'S OWN PAST PAPER TRADES (these are outcomes, not opinions — weigh them):\n");
		for (LessonEffect.Applied a : effect.applied()) {
			sb.append("- ").append(a.description()).append(" [").append(a.stats()).append("]\n");
		}
		return sb.toString();
	}

	/** "n=14 independent bets, win rate 33%, average -3.1% vs the S&P (held-out: -2.4%)". */
	static String stats(LearnedRule r) {
		StringBuilder sb = new StringBuilder(String.format(Locale.ROOT, "%d independent bets", r.getSupportClusters()));
		if (r.getWinRate() != null) {
			sb.append(String.format(Locale.ROOT, ", win rate %.0f%%", r.getWinRate().doubleValue() * 100));
		}
		if (r.getMeanExcess() != null) {
			sb.append(String.format(Locale.ROOT, ", average %+.1f%% vs the S&P", r.getMeanExcess().doubleValue()));
		}
		if (r.getHoldoutMeanExcess() != null && r.getHoldoutClusters() != null) {
			sb.append(String.format(Locale.ROOT, " (held-out newer trades: %+.1f%% over %d bets)", r.getHoldoutMeanExcess().doubleValue(), r.getHoldoutClusters()));
		}
		return sb.toString();
	}
}
