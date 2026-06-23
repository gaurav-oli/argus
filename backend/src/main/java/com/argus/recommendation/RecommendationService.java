package com.argus.recommendation;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Produces and serves Agent 5 recommendations (Story 6.2). It runs the auditable
 * {@link ProbabilityScoringEngine} over the supplied agent signals, persists the resulting
 * {@link Recommendation} together with its per-agent diagnostic, and exposes both the feed and the
 * full signal breakdown (conflicts included) for the diagnostic report.
 */
@Service
public class RecommendationService {

	private final ProbabilityScoringEngine engine;
	private final RecommendationRepository repository;

	public RecommendationService(ProbabilityScoringEngine engine, RecommendationRepository repository) {
		this.engine = engine;
		this.repository = repository;
	}

	/** Score {@code signals} and persist a recommendation for {@code ticker} with its diagnostic. */
	@Transactional
	public Recommendation create(String ticker, List<AgentSignal> signals, BigDecimal priceTarget,
			String horizon) {
		ProbabilityScore score = engine.score(signals);
		return repository.save(new Recommendation(ticker, score, signals, priceTarget, horizon));
	}

	@Transactional(readOnly = true)
	public List<Recommendation> recent() {
		List<Recommendation> recs = repository.findTop50ByOrderByCreatedAtDesc();
		recs.forEach(r -> r.getSignals().size()); // initialize the diagnostic within the tx for the card
		return recs;
	}

	/** A recommendation with its diagnostic signals loaded (Story 6.2). */
	@Transactional(readOnly = true)
	public Optional<Recommendation> diagnostic(Long id) {
		return repository.findWithSignalsById(id);
	}
}
