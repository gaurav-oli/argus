package com.argus.recommendation;

import com.argus.persona.PersonaService;
import com.argus.recommendation.TradeDecision.Decision;
import com.argus.recommendation.TradeDecision.Outcome;
import com.argus.recommendation.TradeDecision.Source;
import jakarta.annotation.PostConstruct;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Records a Taken/Declined decision on a recommendation with an immutable rationale snapshot (Story
 * 6.7, FR-14b). The snapshot freezes the signals, the cached persona verdicts (Story 11.1 — previously
 * an empty Epic-7 seam), and the reasoning at decision time; the realized outcome is recorded later
 * (without touching the snapshot) and, for taken trades, feeds the graduation win-rate (Story 6.6).
 * Entry price / position size (Story 11.1, F22) are optional, Take-only, and human-only.
 *
 * <p>Two sources of decisions: a human confirming a card ({@link #confirm}, {@code Source.USER}) and
 * the Investor persona acting autonomously on its own paper trades ({@link #recordAgentDecision},
 * {@code Source.AGENT}) — see {@link PaperInvestorService#open}. Both can exist for the same
 * recommendation; a human weighing in doesn't erase what the algorithm already did. The Investor acts
 * on every priced, non-duplicate recommendation it gets (no confidence gating today), so its own
 * decisions are, in practice, always Taken — a genuinely agent-driven Decline doesn't exist yet; Decline
 * stays reserved for an actual human pass on a card. A one-time startup pass
 * ({@link #backfillAgentDecisions}) reconciles historical recommendations that predate this wiring.
 */
@Service
public class TradeConfirmationService {

	private static final Logger log = LoggerFactory.getLogger(TradeConfirmationService.class);
	private static final ObjectMapper JSON = JsonMapper.builder().build();

	private final RecommendationRepository recommendations;
	private final TradeDecisionRepository decisions;
	private final GraduationService graduation;
	private final PersonaService personas;
	private final SimulatedTradeRepository simulatedTrades;

	public TradeConfirmationService(RecommendationRepository recommendations,
			TradeDecisionRepository decisions, GraduationService graduation, PersonaService personas,
			SimulatedTradeRepository simulatedTrades) {
		this.recommendations = recommendations;
		this.decisions = decisions;
		this.graduation = graduation;
		this.personas = personas;
		this.simulatedTrades = simulatedTrades;
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
				entryPrice, positionSize, Source.USER));
	}

	/**
	 * Record the Investor persona's own decision on a recommendation — in practice always {@code TAKEN}
	 * (called from {@link PaperInvestorService#open} once it opens or re-affirms a position); the
	 * {@code decision} parameter stays general rather than hardcoding TAKEN, since the caller is the one
	 * that actually knows what happened. Never overwrites an existing decision (human or agent), so this
	 * is a create-once event per recommendation. Re-fetches with signals eagerly loaded (same as
	 * {@link #confirm}) rather than trusting the caller's object state, since the recommendation may
	 * have crossed a transaction boundary by the time the Investor acts on it.
	 */
	@Transactional
	public void recordAgentDecision(Long recommendationId, Decision decision) {
		if (decisions.existsByRecommendationId(recommendationId)) {
			return;
		}
		Recommendation rec = recommendations.findWithSignalsById(recommendationId).orElse(null);
		if (rec == null) {
			return;
		}
		String reasoning = decision == Decision.TAKEN
				? "Investor persona opened a paper position on this call."
				: "Investor persona stayed neutral — no directional conviction to act on.";
		String snapshot = snapshot(rec, decision, reasoning);
		rec.markStatus(decision == Decision.TAKEN ? RecommendationStatus.TAKEN : RecommendationStatus.DECLINED);
		recommendations.save(rec);
		decisions.save(new TradeDecision(recommendationId, decision, reasoning, snapshot, null, null, Source.AGENT));
	}

	/**
	 * One-time reconciliation for recommendations issued before this wiring existed: {@code TAKEN} when
	 * the Investor traded this call — either directly (a {@code simulated_trades} row keyed to this
	 * exact recommendation) or by re-affirming an already-open thesis for the same (ticker, direction)
	 * (a repeat recommendation that only re-affirms never gets its own trade row — the leg keeps
	 * whichever recommendation's id opened it first, per {@link PaperInvestorService#open}). Left alone
	 * otherwise (never priced at the time — genuinely undecided, not a real "declined"). Idempotent —
	 * {@code findMissingDecision} returns nothing once caught up, so this is a cheap no-op on every boot
	 * after the first.
	 */
	@PostConstruct
	@Transactional
	void backfillAgentDecisions() {
		List<Recommendation> undecided = recommendations.findMissingDecision();
		int taken = 0;
		for (Recommendation rec : undecided) {
			if (simulatedTrades.existsByRecommendationId(rec.getId())
					|| simulatedTrades.existsByTickerAndDirection(rec.getTicker(), rec.getDirection())) {
				recordAgentDecision(rec.getId(), Decision.TAKEN);
				taken++;
			}
		}
		if (taken > 0) {
			log.info("Backfilled {} agent decisions (all taken) for historical recommendations", taken);
		}
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
