package com.argus.persona;

import com.argus.model.ModelGateway;
import com.argus.recommendation.Recommendation;
import com.argus.recommendation.RecommendationRepository;
import com.argus.recommendation.RecommendationService;
import com.argus.recommendation.RecommendationSignal;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * F11 — generates the four investor personas' verdicts on a recommendation in a single local-model
 * call, then caches them (re-running the model on every page view would be far too slow). On any
 * model/parse failure it returns a transient CAUTION set rather than persisting, so a flaky local
 * model is simply retried next view.
 */
@Service
public class PersonaService {

	private static final Logger log = LoggerFactory.getLogger(PersonaService.class);
	private static final ObjectMapper JSON = JsonMapper.builder().build();

	/** Single thread: persona generation is serialized to match the single-concurrency big model. */
	private final java.util.concurrent.ExecutorService generationPool =
			java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
				Thread t = new Thread(r, "persona-gen");
				t.setDaemon(true);
				return t;
			});

	private final ModelGateway gateway;
	private final PersonaVerdictRepository verdicts;
	private final RecommendationRepository recommendations;
	private final RecommendationService recommendationService;

	public PersonaService(ModelGateway gateway, PersonaVerdictRepository verdicts,
			RecommendationRepository recommendations, RecommendationService recommendationService) {
		this.gateway = gateway;
		this.verdicts = verdicts;
		this.recommendations = recommendations;
		this.recommendationService = recommendationService;
	}

	/**
	 * Pre-generate persona verdicts for the currently-surfaced recommendations so the cards load
	 * instantly. Runs off-peak of page views; each rec is one slow local-model call, but only once
	 * (cached thereafter), and only for the ~one-card-per-ticker the feed actually shows.
	 */
	@Scheduled(initialDelayString = "${argus.persona.warm-initial-ms:40000}",
			fixedDelayString = "${argus.persona.warm-ms:300000}")
	public void warmCurrent() {
		for (Long id : recommendationService.currentRecommendationIds()) {
			if (!verdicts.existsByRecommendationId(id)) {
				try {
					generate(id);
				}
				catch (RuntimeException ex) {
					log.debug("Persona warm failed for rec {}: {}", id, ex.getMessage());
				}
			}
		}
	}

	/**
	 * Cached verdicts for a recommendation. Never blocks on the model: if not yet generated it kicks
	 * off background generation (serialized on a single thread, matching the big model) and returns a
	 * transient "warming" set immediately, so the page loads fast and the card fills in on the next poll.
	 */
	public List<PersonaVerdict> verdictsFor(Long recommendationId) {
		List<PersonaVerdict> cached = verdicts.findByRecommendationIdOrderByPersona(recommendationId);
		if (!cached.isEmpty()) {
			return cached;
		}
		generationPool.submit(() -> {
			try {
				generate(recommendationId);
			}
			catch (RuntimeException ex) {
				log.debug("Background persona generation failed for rec {}: {}", recommendationId, ex.getMessage());
			}
		});
		return transientFallback(recommendationId);
	}

	// Serialized: the big model is single-concurrency anyway, and this collapses duplicate
	// generation when several cards request the same rec at once.
	private synchronized List<PersonaVerdict> generate(Long recommendationId) {
		List<PersonaVerdict> cached = verdicts.findByRecommendationIdOrderByPersona(recommendationId);
		if (!cached.isEmpty()) {
			return cached;
		}
		Recommendation rec = recommendations.findWithSignalsById(recommendationId).orElse(null);
		if (rec == null) {
			return List.of();
		}
		try {
			Map<Persona, PersonaVerdict> parsed = callModel(rec);
			List<PersonaVerdict> toSave = new ArrayList<>();
			for (Persona p : Persona.values()) {
				toSave.add(parsed.getOrDefault(p, new PersonaVerdict(recommendationId, p, PersonaStance.CAUTION,
						"No clear read on this one.")));
			}
			return verdicts.saveAll(toSave);
		}
		catch (RuntimeException ex) {
			log.warn("Persona generation failed for rec {}: {}", recommendationId, ex.getMessage());
			return transientFallback(recommendationId); // not persisted — retried next view
		}
	}

	private Map<Persona, PersonaVerdict> callModel(Recommendation rec) {
		String raw = gateway.generate(prompt(rec)); // BIG (local) tier
		JsonNode array = JSON.readTree(extractJsonArray(raw));
		Map<Persona, PersonaVerdict> out = new EnumMap<>(Persona.class);
		for (JsonNode node : array) {
			Persona persona = Persona.fromKey(node.path("persona").asString());
			if (persona == null || out.containsKey(persona)) {
				continue;
			}
			PersonaStance stance = PersonaStance.fromText(node.path("stance").asString());
			String rationale = node.path("rationale").asString("").strip();
			out.put(persona, new PersonaVerdict(rec.getId(), persona, stance,
					rationale.isBlank() ? "—" : rationale));
		}
		if (out.isEmpty()) {
			throw new IllegalStateException("model returned no parseable persona verdicts");
		}
		return out;
	}

	private static String prompt(Recommendation rec) {
		StringBuilder evidence = new StringBuilder();
		for (RecommendationSignal s : rec.getSignals()) {
			evidence.append("- ").append(s.getAgent()).append(" → ").append(s.getDirection())
					.append(": ").append(s.getRationale() == null ? "" : s.getRationale()).append('\n');
		}
		double bull = rec.getBullProbability() == null ? 50 : rec.getBullProbability().doubleValue() * 100;
		double conf = rec.getConfidence() == null ? 0 : rec.getConfidence().doubleValue() * 100;
		return """
				You simulate four famous investor personas reacting to an automated stock recommendation. \
				Stay in character and be concise.

				RECOMMENDATION: %s — %s (%.0f%% bullish, confidence %.0f%%)
				EVIDENCE from the analysis agents:
				%s
				For EACH persona, choose a stance toward this recommendation — AGREE, DISAGREE, or CAUTION — \
				and give ONE short sentence (max ~20 words) of reasoning in that persona's voice:
				1. buffett — Warren Buffett: value, durable moats, long-term, circle of competence
				2. lynch — Peter Lynch: growth at a reasonable price, know what you own
				3. devils_advocate — argue the bear case and the risks, whatever the recommendation says
				4. canadian — Canadian investor: TFSA/RRSP tax efficiency, CAD/USD, US withholding tax

				Respond with ONLY a JSON array, no prose, no markdown fences:
				[{"persona":"buffett","stance":"AGREE","rationale":"..."}, {"persona":"lynch",...}, ...]
				""".formatted(rec.getTicker(), rec.getDirection(), bull, conf, evidence);
	}

	/** Pull the JSON array out of a model response that may include prose or markdown fences. */
	private static String extractJsonArray(String raw) {
		if (raw == null) {
			throw new IllegalStateException("empty model response");
		}
		int start = raw.indexOf('[');
		int end = raw.lastIndexOf(']');
		if (start < 0 || end <= start) {
			throw new IllegalStateException("no JSON array in model response");
		}
		return raw.substring(start, end + 1);
	}

	private static List<PersonaVerdict> transientFallback(Long recommendationId) {
		List<PersonaVerdict> out = new ArrayList<>();
		for (Persona p : Persona.values()) {
			out.add(new PersonaVerdict(recommendationId, p, PersonaStance.CAUTION,
					"Persona analysis is still warming up — check back shortly."));
		}
		return out;
	}
}
