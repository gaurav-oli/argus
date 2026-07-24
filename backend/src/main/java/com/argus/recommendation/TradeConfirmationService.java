package com.argus.recommendation;

import com.argus.persona.PersonaService;
import com.argus.recommendation.TradeDecision.Decision;
import com.argus.recommendation.TradeDecision.Outcome;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Records the user's Taken/Declined decision on a recommendation with an immutable rationale snapshot
 * (Story 6.7, FR-14b). The snapshot freezes the signals, the cached persona verdicts (Story 11.1 —
 * previously an empty Epic-7 seam), and the user's reasoning at decision time; the realized outcome is
 * recorded later (without touching the snapshot) and, for taken trades, feeds the graduation win-rate
 * (Story 6.6). Entry price / position size (Story 11.1, F22) are optional and Take-only.
 */
@Service
public class TradeConfirmationService {

	private static final ObjectMapper JSON = JsonMapper.builder().build();

	private final RecommendationRepository recommendations;
	private final TradeDecisionRepository decisions;
	private final GraduationService graduation;
	private final PersonaService personas;

	public TradeConfirmationService(RecommendationRepository recommendations,
			TradeDecisionRepository decisions, GraduationService graduation, PersonaService personas) {
		this.recommendations = recommendations;
		this.decisions = decisions;
		this.graduation = graduation;
		this.personas = personas;
	}

	/** Mark a recommendation taken/declined, freezing a snapshot of its signals + the reasoning.
	 * {@code entryPrice}/{@code positionSize} are optional and only meaningful for a Taken decision;
	 * pass null for either (or both) when not reported. */
	@Transactional
	public TradeDecision confirm(Long recommendationId, Decision decision, String reasoning,
			BigDecimal entryPrice, BigDecimal positionSize) {
		Recommendation rec = recommendations.findWithSignalsById(recommendationId)
				.orElseThrow(() -> new IllegalArgumentException("No recommendation " + recommendationId));
		String snapshot = snapshot(rec, decision, reasoning);
		rec.markStatus(decision == Decision.TAKEN ? RecommendationStatus.TAKEN : RecommendationStatus.DECLINED);
		recommendations.save(rec);
		return decisions.save(new TradeDecision(recommendationId, decision, reasoning, snapshot,
				entryPrice, positionSize));
	}

	/** Record the realized outcome; a taken trade's result updates the graduation win-rate. */
	@Transactional
	public void recordOutcome(Long decisionId, boolean won) {
		TradeDecision d = decisions.findById(decisionId)
				.orElseThrow(() -> new IllegalArgumentException("No decision " + decisionId));
		boolean firstResult = d.getOutcome() == null;
		d.recordOutcome(won ? Outcome.WIN : Outcome.LOSS);
		decisions.save(d);
		if (firstResult && d.getDecision() == Decision.TAKEN) {
			graduation.recordOutcome(won, d.getRecommendationId());
		}
	}

	/**
	 * Record the outcome the Investor's paper trade realized onto any decision the user made on that
	 * recommendation (regret analysis — the behavioral mirror). Idempotent per decision (the entity
	 * keeps the first outcome) and deliberately does NOT feed graduation:
	 * {@code PaperInvestorService.closeOne} already records the trade outcome there, so routing
	 * through {@link #recordOutcome} would double-count.
	 */
	@Transactional
	public void recordOutcomeFromPaperTrade(Long recommendationId, boolean won) {
		if (recommendationId == null) {
			return;
		}
		for (TradeDecision d : decisions.findByRecommendationId(recommendationId)) {
			d.recordOutcome(won ? Outcome.WIN : Outcome.LOSS);
			decisions.save(d);
		}
	}

	private String snapshot(Recommendation rec, Decision decision, String reasoning) {
		Map<String, Object> snap = new LinkedHashMap<>();
		snap.put("ticker", rec.getTicker());
		snap.put("direction", rec.getDirection().name());
		snap.put("bullProbability", rec.getBullProbability());
		snap.put("bearProbability", rec.getBearProbability());
		snap.put("confidence", rec.getConfidence());
		snap.put("decision", decision.name());
		snap.put("reasoning", reasoning);
		snap.put("capturedAt", Instant.now().toString());
		snap.put("signals", rec.getSignals().stream().map(s -> Map.of(
				"agent", s.getAgent(),
				"direction", s.getDirection().name(),
				"weight", s.getWeight(),
				"rationale", s.getRationale() == null ? "" : s.getRationale())).toList());
		snap.put("personaVerdicts", personaSnapshot(rec.getId()));
		return JSON.writeValueAsString(snap);
	}

	/** Freezes whatever persona verdicts are already cached at decision time (Story 11.1) — cache-only,
	 * so confirming a decision never triggers a persona-generation model call as a side effect. Simply
	 * empty when nothing is cached yet, same as the pre-Story-11.1 seam this replaces. */
	private List<Map<String, String>> personaSnapshot(Long recommendationId) {
		return personas.cachedVerdictsFor(recommendationId).stream()
				.map(v -> Map.of(
						"persona", v.getPersona().displayName(),
						"key", v.getPersona().name(),
						"stance", v.getStance().name(),
						"rationale", v.getRationale() == null ? "" : v.getRationale()))
				.toList();
	}
}
