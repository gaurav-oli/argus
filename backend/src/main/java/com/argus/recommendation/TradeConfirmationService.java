package com.argus.recommendation;

import com.argus.recommendation.TradeDecision.Decision;
import com.argus.recommendation.TradeDecision.Outcome;
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
 * (Story 6.7, FR-14b). The snapshot freezes the signals + the user's reasoning at decision time; the
 * realized outcome is recorded later (without touching the snapshot) and, for taken trades, feeds the
 * graduation win-rate (Story 6.6). Persona verdicts (Epic 7) are an empty seam in the snapshot for now.
 */
@Service
public class TradeConfirmationService {

	private static final ObjectMapper JSON = JsonMapper.builder().build();

	private final RecommendationRepository recommendations;
	private final TradeDecisionRepository decisions;
	private final GraduationService graduation;

	public TradeConfirmationService(RecommendationRepository recommendations,
			TradeDecisionRepository decisions, GraduationService graduation) {
		this.recommendations = recommendations;
		this.decisions = decisions;
		this.graduation = graduation;
	}

	/** Mark a recommendation taken/declined, freezing a snapshot of its signals + the reasoning. */
	@Transactional
	public TradeDecision confirm(Long recommendationId, Decision decision, String reasoning) {
		Recommendation rec = recommendations.findWithSignalsById(recommendationId)
				.orElseThrow(() -> new IllegalArgumentException("No recommendation " + recommendationId));
		String snapshot = snapshot(rec, decision, reasoning);
		rec.markStatus(decision == Decision.TAKEN ? RecommendationStatus.TAKEN : RecommendationStatus.DECLINED);
		recommendations.save(rec);
		return decisions.save(new TradeDecision(recommendationId, decision, reasoning, snapshot));
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

	private static String snapshot(Recommendation rec, Decision decision, String reasoning) {
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
		snap.put("personaVerdicts", List.of()); // Epic 7 seam
		return JSON.writeValueAsString(snap);
	}
}
