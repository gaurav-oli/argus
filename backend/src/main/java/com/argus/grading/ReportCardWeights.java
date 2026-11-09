package com.argus.grading;

import com.argus.recommendation.AgentSignal;
import com.argus.recommendation.SignalDirection;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Applies an agent's report-card weight to one of its signals (strategy rebuild, Phase 1).
 *
 * <ul>
 *   <li>A pinned weight always wins (the manual override).</li>
 *   <li>Otherwise the weight for today's situation — earnings week, then market mood — is used once that situation
 *       has its own evidence; before that, the agent's overall weight.</li>
 *   <li>Weight 0 mutes the agent: its signal is dropped, so it doesn't even count toward coverage.</li>
 *   <li>A negative weight flips it: the signal is read the other way, at the absolute weight.</li>
 *   <li>An agent whose strong calls aren't more often right than its weak ones has its stated strength ignored —
 *       every call is weighed at its average strength.</li>
 * </ul>
 */
@Component
public class ReportCardWeights {

	private final ReportCardService cards;

	public ReportCardWeights(ReportCardService cards) {
		this.cards = cards;
	}

	/**
	 * @param riskOff      whether today's market mood is risk-off
	 * @param earningsWeek whether the stock reports soon
	 */
	public Optional<AgentSignal> apply(AgentSignal s, boolean riskOff, boolean earningsWeek) {
		Optional<ReportCardService.Weight> w = cards.weight(s.agent());
		if (w.isEmpty() || s.direction() == SignalDirection.NEUTRAL) {
			return Optional.of(s);
		}
		double m = multiplierFor(w.get(), riskOff, earningsWeek);
		boolean ignoreStrength = Boolean.FALSE.equals(w.get().strengthUseful()) && w.get().meanStrength() > 0;
		if (m == 1.0 && !ignoreStrength) {
			return Optional.of(s);
		}
		if (m == 0.0) {
			return Optional.empty();
		}
		double base = ignoreStrength ? w.get().meanStrength() : s.weight();
		if (m < 0) {
			SignalDirection flipped = s.direction() == SignalDirection.BULLISH ? SignalDirection.BEARISH : SignalDirection.BULLISH;
			return Optional.of(new AgentSignal(s.agent(), flipped, base * -m,
					s.rationale() + " [read the other way: this agent's calls have been reliably wrong]", s.horizonHintDays()));
		}
		return Optional.of(new AgentSignal(s.agent(), s.direction(), base * m, s.rationale(), s.horizonHintDays()));
	}

	static double multiplierFor(ReportCardService.Weight w, boolean riskOff, boolean earningsWeek) {
		if (w.pinned() != null) {
			return w.pinned();
		}
		Map<String, Double> sits = w.situational();
		Map<String, Integer> n = w.situationClusters();
		if (earningsWeek && n.getOrDefault(ReportCardMath.EARNINGS, 0) >= ReportCardMath.SITUATION_MIN_CLUSTERS
				&& sits.containsKey(ReportCardMath.EARNINGS)) {
			return sits.get(ReportCardMath.EARNINGS);
		}
		String mood = riskOff ? ReportCardMath.RISK_OFF : ReportCardMath.CALM;
		if (n.getOrDefault(mood, 0) >= ReportCardMath.SITUATION_MIN_CLUSTERS && sits.containsKey(mood)) {
			return sits.get(mood);
		}
		return w.multiplier();
	}
}
