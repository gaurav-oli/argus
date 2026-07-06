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
	private final AdaptiveTuningService tuning;

	public RecommendationService(ProbabilityScoringEngine engine, RecommendationRepository repository,
			AdaptiveTuningService tuning) {
		this.engine = engine;
		this.repository = repository;
		this.tuning = tuning;
	}

	/** Score {@code signals} and persist a recommendation for {@code ticker} with its diagnostic. */
	@Transactional
	public Recommendation create(String ticker, List<AgentSignal> signals, BigDecimal priceTarget,
			String horizon) {
		ProbabilityScore score = calibrate(engine.score(signals));
		return repository.save(new Recommendation(ticker, score, signals, priceTarget, horizon));
	}

	/**
	 * Phase B: nudge the stated directional probability toward the realized hit rate (isotonic
	 * calibration). The engine stays pure — this adjusts only the reported probability, never the
	 * audit-trail scores/contributions, and is the identity when tuning is disabled or uncalibrated.
	 */
	private ProbabilityScore calibrate(ProbabilityScore s) {
		boolean bullish = s.bullProbability() >= 0.5;
		double stated = bullish ? s.bullProbability() : s.bearProbability();
		double calibrated = tuning.calibrateDirectionalProbability(stated);
		if (calibrated == stated) {
			return s;
		}
		double bull = bullish ? calibrated : 1.0 - calibrated;
		return new ProbabilityScore(bull, 1.0 - bull, s.confidence(), s.bullScore(), s.bearScore(),
				s.contributions());
	}

	@Transactional(readOnly = true)
	public List<Recommendation> recent() {
		// One current card per ticker. Each trigger (6h review, stranger event) appends a new row, so
		// the feed would otherwise show the same holding many times. Among a ticker's recent rows show
		// the RICHEST one — most agent signals — with newest as the tiebreaker, so a thin rec (e.g. a
		// burst where one trigger only caught news) never hides the complete analysis beside it.
		java.util.Map<String, Recommendation> bestByTicker = new java.util.LinkedHashMap<>();
		for (Recommendation r : repository.findTop50ByOrderByCreatedAtDesc()) {
			r.getSignals().size(); // initialize the diagnostic within the tx
			bestByTicker.merge(r.getTicker(), r,
					(newer, older) -> older.getSignals().size() > newer.getSignals().size() ? older : newer);
		}
		return new java.util.ArrayList<>(bestByTicker.values());
	}

	/** IDs of the currently-surfaced recommendations (one per ticker) — for persona pre-warming. */
	@Transactional(readOnly = true)
	public List<Long> currentRecommendationIds() {
		return recent().stream().map(Recommendation::getId).toList();
	}

	/** A recommendation with its diagnostic signals loaded (Story 6.2). */
	@Transactional(readOnly = true)
	public Optional<Recommendation> diagnostic(Long id) {
		return repository.findWithSignalsById(id);
	}
}
