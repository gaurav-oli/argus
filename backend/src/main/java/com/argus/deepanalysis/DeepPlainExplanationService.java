package com.argus.deepanalysis;

import com.argus.model.ModelGateway;
import java.util.Locale;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * "Explain this like I'm new to investing" for a finished Agent 11 verdict — the same job
 * {@link com.argus.intelligence.BreakingAlertCurationService} already does for breaking news, applied to the
 * thesis/bull case/bear case instead. Deliberately on-demand rather than generated for every analysis: most
 * verdicts (especially WAIT, the most common one) are never read in enough depth to need this, so paying for
 * the extra Gemma call only when someone actually asks keeps the nightly/on-demand pipeline no slower.
 *
 * <p>Gemma only rephrases an already-reached verdict here — it is never asked to reconsider it, so this can
 * never change what Argus decided, only how plainly it's explained. Cached on the row once generated.
 */
@Service
public class DeepPlainExplanationService {

	private static final Logger log = LoggerFactory.getLogger(DeepPlainExplanationService.class);

	private final DeepAnalysisRepository repository;
	private final ModelGateway gateway;

	public DeepPlainExplanationService(DeepAnalysisRepository repository, ModelGateway gateway) {
		this.repository = repository;
		this.gateway = gateway;
	}

	/** Cached text if already generated, else generate now, persist, and return it. Empty only for an unknown id. */
	public Optional<String> explain(Long analysisId) {
		DeepAnalysis run = repository.findById(analysisId).orElse(null);
		if (run == null) {
			return Optional.empty();
		}
		if (run.getPlainExplanation() != null) {
			return Optional.of(run.getPlainExplanation());
		}
		if (run.getVerdict() == null) {
			return Optional.empty(); // nothing finished yet to explain
		}
		String text = generate(run);
		run.recordPlainExplanation(text);
		repository.save(run);
		return Optional.of(text);
	}

	private String generate(DeepAnalysis run) {
		String prompt = DeepPrompts.plainExplanation(run.getTicker(), run.getVerdict().label(), run.getHoldDays(), run.getHeadline(),
				run.getThesis(), run.getBullCase(), run.getBearCase(), run.getInvalidation());
		try {
			String raw = gateway.generate(prompt);
			if (raw != null && !raw.isBlank()) {
				return raw.strip();
			}
		}
		catch (RuntimeException ex) {
			log.warn("Agent 11: plain-explanation call failed for {} ({}) — using the deterministic fallback", run.getTicker(), ex.getMessage());
		}
		return fallback(run);
	}

	/** Fails safe, same posture as the breaking-news curator: a model hiccup never means no explanation at all. */
	private static String fallback(DeepAnalysis run) {
		return String.format(Locale.ROOT, """
				Argus decided %s is %s%s.

				%s

				%s

				What would change this: %s""",
				run.getTicker(), run.getVerdict().label().toLowerCase(Locale.ROOT),
				run.getHoldDays() == null ? "" : " (hold about " + run.getHoldDays() + " days)",
				run.getThesis() == null || run.getThesis().isBlank() ? "" : run.getThesis(),
				run.getBullCase() == null || run.getBullCase().isBlank() ? "" : "The case for it: " + run.getBullCase(),
				run.getInvalidation() == null || run.getInvalidation().isBlank() ? "not specified" : run.getInvalidation())
				.strip();
	}
}
