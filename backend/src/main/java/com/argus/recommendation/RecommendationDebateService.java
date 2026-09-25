package com.argus.recommendation;

import com.argus.model.LenientJsonParser;
import com.argus.model.ModelGateway;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

/**
 * On-demand bull-vs-bear researcher debate (TradingAgents paper's Researcher Team pattern), adapted
 * to Argus's cost/latency constraints: rather than a 3-round back-and-forth (bull call, bear call,
 * synthesis call — 3x the cost/latency of an already-fragile paid-escalation path, see
 * docs/mac-mini-validation.md), this is ONE {@link ModelGateway#escalate(String)} call asking the
 * model to produce the bull case, bear case, and synthesis verdict together as structured JSON — the
 * same single-classification-call idiom as {@code CauseClassificationService}/{@code
 * LogicReviewService}. User-triggered only ("Debate this call"); never wired into {@link
 * RecommendationTrigger}'s automatic 6-hourly review, and deliberately not grounded through {@code
 * RecommendationContextAssembler} (that lives in {@code com.argus.conversation}, which already
 * depends on this package — reusing it here would invert that dependency, and it pulls in portfolio
 * context this single-stock debate doesn't need).
 */
@Service
public class RecommendationDebateService {

	private static final Logger log = LoggerFactory.getLogger(RecommendationDebateService.class);

	private final RecommendationRepository recommendations;
	private final RecommendationDebateRepository debates;
	private final ModelGateway gateway;

	public RecommendationDebateService(RecommendationRepository recommendations,
			RecommendationDebateRepository debates, ModelGateway gateway) {
		this.recommendations = recommendations;
		this.debates = debates;
		this.gateway = gateway;
	}

	/** Run one debate for {@code recommendationId} and persist it. Empty when the recommendation
	 * doesn't exist, the model call fails, or the response can't be parsed — never throws. */
	@Transactional
	public Optional<RecommendationDebate> debate(Long recommendationId) {
		Recommendation rec = recommendations.findWithSignalsById(recommendationId).orElse(null);
		if (rec == null) {
			return Optional.empty();
		}
		String prompt = buildPrompt(rec);
		String raw;
		try {
			raw = gateway.escalate(prompt);
		}
		catch (RuntimeException ex) {
			log.warn("Debate for recommendation {} failed: {}", recommendationId, ex.getMessage());
			return Optional.empty();
		}
		return parse(raw).map(d -> debates.save(new RecommendationDebate(recommendationId, d.bullCase(),
				d.bearCase(), d.synthesis(), d.verdict(), "haiku")));
	}

	@Transactional(readOnly = true)
	public List<RecommendationDebate> historyFor(Long recommendationId) {
		return debates.findByRecommendationIdOrderByCreatedAtDesc(recommendationId);
	}

	// ---- prompt ----

	private static String buildPrompt(Recommendation rec) {
		StringBuilder signals = new StringBuilder();
		for (RecommendationSignal s : rec.getSignals()) {
			signals.append("- ").append(s.getAgent()).append(": ").append(s.getDirection())
					.append(" (weight ").append(s.getWeight()).append(')');
			if (s.getRationale() != null && !s.getRationale().isBlank()) {
				signals.append(" — ").append(s.getRationale());
			}
			signals.append('\n');
		}
		return """
				You are running a structured bull-vs-bear research debate on a single trade call, in the \
				style of a professional investment committee. Below is Agent 5's auditable probability \
				call for %s and the per-agent signals it was built from.

				CALL: %s, bull %.1f%%, bear %.1f%%, confidence %.1f%%, target %s, horizon %s

				SIGNALS:
				%s
				Produce THREE things: (1) the strongest good-faith BULL case using only the evidence above \
				(don't invent facts not in the signals), (2) the strongest good-faith BEAR case likewise, \
				(3) a synthesis verdict weighing both. The bull and bear cases should genuinely argue \
				against each other, not just restate the direction already called.

				Respond with ONLY a JSON object, no prose: {"bullCase":"...","bearCase":"...",\
				"synthesis":"...","verdict":"BULL"|"BEAR"|"SPLIT"}
				""".formatted(rec.getTicker(), rec.getDirection(), rec.getBullProbability().doubleValue() * 100,
				rec.getBearProbability().doubleValue() * 100, rec.getConfidence().doubleValue() * 100,
				rec.getPriceTarget(), rec.getHorizon(), signals);
	}

	// ---- parsing ----

	record Parsed(String bullCase, String bearCase, String synthesis, DebateVerdict verdict) {
	}

	static Optional<Parsed> parse(String raw) {
		return LenientJsonParser.parseObject(raw, log).flatMap(RecommendationDebateService::toParsed);
	}

	private static Optional<Parsed> toParsed(JsonNode n) {
		String bullCase = n.path("bullCase").asString("").trim();
		String bearCase = n.path("bearCase").asString("").trim();
		String synthesis = n.path("synthesis").asString("").trim();
		if (bullCase.isEmpty() || bearCase.isEmpty() || synthesis.isEmpty()) {
			return Optional.empty(); // never persist a half-formed debate
		}
		DebateVerdict verdict = parseVerdict(n.path("verdict").asString("").trim());
		if (verdict == null) {
			return Optional.empty(); // never guess (CauseClassificationService precedent)
		}
		return Optional.of(new Parsed(bullCase, bearCase, synthesis, verdict));
	}

	private static DebateVerdict parseVerdict(String raw) {
		try {
			return DebateVerdict.valueOf(raw.toUpperCase(Locale.ROOT));
		}
		catch (IllegalArgumentException ex) {
			return null;
		}
	}
}
