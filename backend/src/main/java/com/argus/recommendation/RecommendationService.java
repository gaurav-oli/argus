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

	/** Score {@code signals}, attach the policy's {@code verdict}, and persist (the trigger's path). */
	@Transactional
	public Recommendation create(String ticker, List<AgentSignal> signals, java.util.function.Function<ProbabilityScore,
			RecommendationPolicy.Verdict> verdictFor, String sector, String horizon) {
		ProbabilityScore score = calibrate(engine.score(signals));
		Recommendation rec = new Recommendation(ticker, score, signals, null, horizon);
		rec.applyVerdict(verdictFor.apply(score), sector);
		return repository.save(rec);
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

	/** How far back a ticker's latest read still counts as "current" for the feed. */
	private static final java.time.Duration CURRENT_WINDOW = java.time.Duration.ofDays(3);

	/** One six-hourly review pass plus an hour of slack: a read older than this, relative to the newest
	 * one, was skipped by the latest pass and is no longer current. */
	static final java.time.Duration LAST_PASS_WINDOW = java.time.Duration.ofHours(7);

	/**
	 * The current, <b>actionable</b> calls: each ticker's latest recommendation, kept only if it is a
	 * BUY/AVOID (or a legacy verdict-less row), strongest conviction first. A ticker whose latest read is
	 * WATCH is deliberately absent — see {@link #watching()}.
	 */
	@Transactional(readOnly = true)
	public List<Recommendation> recent() {
		return latestPerTicker().stream().filter(Recommendation::isActionable)
				.sorted(java.util.Comparator
						.comparing((Recommendation r) -> r.getConvictionScore() == null ? -1 : r.getConvictionScore())
						.thenComparing(Recommendation::getCreatedAt).thenComparing(Recommendation::getId).reversed())
				.toList();
	}

	/** Tickers Argus is watching but has no clear edge on, most-recent first, with the reason why. */
	@Transactional(readOnly = true)
	public List<Recommendation> watching() {
		return latestPerTicker().stream().filter(r -> !r.isActionable())
				.sorted(java.util.Comparator.comparing(Recommendation::getTicker)).toList();
	}

	/**
	 * Each ticker's latest read — but only if the most recent review pass re-scored it. A ticker that
	 * dropped out of the universe (an expired "trending" discovery, a sold holding) or is in an earnings
	 * quiet period gets no new row, and its last call must not linger as if it were still live. Measured
	 * from the newest row rather than the clock, so a host outage shows the last pass instead of nothing.
	 */
	private List<Recommendation> latestPerTicker() {
		List<Recommendation> latest = repository.latestPerTickerSince(
				java.time.Instant.now().minus(CURRENT_WINDOW));
		java.util.Optional<java.time.Instant> newest = latest.stream().map(Recommendation::getCreatedAt)
				.max(java.util.Comparator.naturalOrder());
		if (newest.isEmpty()) {
			return latest;
		}
		java.time.Instant cutoff = newest.get().minus(LAST_PASS_WINDOW);
		List<Recommendation> current = latest.stream().filter(r -> !r.getCreatedAt().isBefore(cutoff)).toList();
		current.forEach(r -> r.getSignals().size()); // initialize the diagnostic within the tx
		return current;
	}

	/** When each recommendation's current call (same action, unbroken) was first made, keyed by id. */
	@Transactional(readOnly = true)
	public java.util.Map<Long, java.time.Instant> callSince(java.util.Collection<Long> ids) {
		java.util.Map<Long, java.time.Instant> since = new java.util.HashMap<>();
		if (ids.isEmpty()) {
			return since;
		}
		for (Object[] row : repository.callSince(ids)) {
			if (row[1] != null) {
				since.put(((Number) row[0]).longValue(), toInstant(row[1]));
			}
		}
		return since;
	}

	private static java.time.Instant toInstant(Object value) {
		if (value instanceof java.time.Instant i) {
			return i;
		}
		if (value instanceof java.time.OffsetDateTime o) {
			return o.toInstant();
		}
		return ((java.sql.Timestamp) value).toInstant();
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
