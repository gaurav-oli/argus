package com.argus.recommendation;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Read-only Trade Journal (Story 11.1, F22): every Taken/Declined decision, most-recent-first, with
 * its frozen FR-15 rationale snapshot and — once a matching paper leg has closed — how Agent 5's call
 * actually played out. Reads {@code ticker}/{@code direction}/signals/persona verdicts from the frozen
 * {@code snapshot} JSON rather than joining live {@link Recommendation} state, since the journal is
 * deliberately a historical record of what was true at decision time, not the recommendation's
 * current state.
 */
@Service
public class JournalService {

	private static final ObjectMapper JSON = JsonMapper.builder().build();

	private final TradeDecisionRepository decisions;
	private final PerformanceService performance;

	public JournalService(TradeDecisionRepository decisions, PerformanceService performance) {
		this.decisions = decisions;
		this.performance = performance;
	}

	@Transactional(readOnly = true)
	public List<JournalEntryView> list() {
		Map<Long, Double> avgReturnByRec = performance.avgReturnByRecommendation();
		return decisions.findAllByOrderByDecidedAtDesc().stream()
				.map(d -> toEntryView(d, avgReturnByRec.get(d.getRecommendationId())))
				.toList();
	}

	@Transactional(readOnly = true)
	public Optional<JournalDetailView> detail(Long decisionId) {
		Map<Long, Double> avgReturnByRec = performance.avgReturnByRecommendation();
		return decisions.findById(decisionId)
				.map(d -> toDetailView(d, avgReturnByRec.get(d.getRecommendationId())));
	}

	private JournalEntryView toEntryView(TradeDecision d, Double avgReturnPct) {
		JsonNode snap = parseSnapshot(d.getSnapshot());
		return new JournalEntryView(d.getId(), d.getRecommendationId(),
				snap.path("ticker").asString(""), snap.path("direction").asString(""),
				d.getDecision().name(), d.getSource().name(), d.getDecidedAt(), outcomeOf(avgReturnPct), avgReturnPct);
	}

	private JournalDetailView toDetailView(TradeDecision d, Double avgReturnPct) {
		JsonNode snap = parseSnapshot(d.getSnapshot());
		List<SignalDetail> signals = new ArrayList<>();
		for (JsonNode s : snap.path("signals")) {
			signals.add(new SignalDetail(s.path("agent").asString(""), s.path("direction").asString(""),
					s.path("weight").decimalValue(null), s.path("rationale").asString("")));
		}
		List<PersonaVerdictDetail> personaVerdicts = new ArrayList<>();
		for (JsonNode p : snap.path("personaVerdicts")) {
			personaVerdicts.add(new PersonaVerdictDetail(p.path("persona").asString(""), p.path("key").asString(""),
					p.path("stance").asString(""), p.path("rationale").asString("")));
		}
		return new JournalDetailView(d.getId(), d.getRecommendationId(),
				snap.path("ticker").asString(""), snap.path("direction").asString(""),
				snap.path("bullProbability").decimalValue(null),
				snap.path("bearProbability").decimalValue(null),
				snap.path("confidence").decimalValue(null),
				d.getDecision().name(), d.getSource().name(), d.getReasoning(), d.getDecidedAt(),
				d.getEntryPrice(), d.getPositionSize(), signals, personaVerdicts,
				outcomeOf(avgReturnPct), avgReturnPct);
	}

	/** Win/Loss mirrors regret()'s own per-return bucketing (a positive average return is a win);
	 * Pending means no closed paper leg exists for this recommendation yet. */
	private static String outcomeOf(Double avgReturnPct) {
		if (avgReturnPct == null) {
			return "PENDING";
		}
		return avgReturnPct > 0 ? "WIN" : "LOSS";
	}

	private static JsonNode parseSnapshot(String snapshot) {
		return JSON.readTree(snapshot);
	}

	// ---- DTOs ----

	/** One journal row. {@code outcome} is WIN/LOSS/PENDING; {@code outcomeReturnPct} is null when
	 * pending. {@code source} is USER (a card confirmed by hand) or AGENT (the Investor persona acting
	 * on its own paper trades). */
	public record JournalEntryView(Long decisionId, Long recommendationId, String ticker, String direction,
			String decision, String source, java.time.Instant decidedAt, String outcome, Double outcomeReturnPct) {
	}

	/** Full frozen snapshot + entry details + the same outcome derivation as the list view. */
	public record JournalDetailView(Long decisionId, Long recommendationId, String ticker, String direction,
			BigDecimal bullProbability, BigDecimal bearProbability, BigDecimal confidence,
			String decision, String source, String reasoning, java.time.Instant decidedAt,
			BigDecimal entryPrice, BigDecimal positionSize,
			List<SignalDetail> signals, List<PersonaVerdictDetail> personaVerdicts,
			String outcome, Double outcomeReturnPct) {
	}

	public record SignalDetail(String agent, String direction, BigDecimal weight, String rationale) {
	}

	public record PersonaVerdictDetail(String persona, String key, String stance, String rationale) {
	}
}
