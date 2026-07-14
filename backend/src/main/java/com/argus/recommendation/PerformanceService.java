package com.argus.recommendation;

import com.argus.recommendation.GraduationService.GraduationSummary;
import com.argus.recommendation.RecommendationSignalRepository.AgentWeightAggregate;
import com.argus.recommendation.TradeDecision.Decision;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Agent 5 performance analytics for the Operations dashboards (Epic 9): accuracy over time windows
 * (Story 9.2), per-agent contribution attribution (Story 9.3), and probability calibration
 * (Story 9.4). All figures are derived from real recorded outcomes — never the LLM.
 *
 * <p>Honesty notes baked into the output: win rates below {@link #MEANINGFUL_TRADES} are flagged
 * {@code statisticallyMeaningful=false}, and calibration bins below {@link #CALIBRATION_MIN_SAMPLE}
 * are flagged {@code sufficient=false}. "Average gains" is intentionally absent — outcomes are
 * recorded as a win/loss boolean ({@link PaperTrade}) with no P&L, so a gains figure would be invented.
 */
@Service
public class PerformanceService {

	/** Below this many resolved trades, a win rate is shown but labelled not statistically meaningful. */
	static final int MEANINGFUL_TRADES = 20;
	/** Below this many samples, a calibration bin is shown but labelled insufficient. */
	static final int CALIBRATION_MIN_SAMPLE = 5;
	static final int CALIBRATION_BINS = 10;

	private final PaperTradeRepository trades;
	private final TradeDecisionRepository decisions;
	private final RecommendationRepository recommendations;
	private final RecommendationSignalRepository signals;
	private final GraduationService graduation;
	private final AdaptiveTuningService tuning;
	private final SimulatedTradeRepository simulatedTrades;

	public PerformanceService(PaperTradeRepository trades, TradeDecisionRepository decisions,
			RecommendationRepository recommendations, RecommendationSignalRepository signals,
			GraduationService graduation, AdaptiveTuningService tuning,
			SimulatedTradeRepository simulatedTrades) {
		this.trades = trades;
		this.decisions = decisions;
		this.recommendations = recommendations;
		this.signals = signals;
		this.graduation = graduation;
		this.tuning = tuning;
		this.simulatedTrades = simulatedTrades;
	}

	// ---- Story 9.2: accuracy ----

	@Transactional(readOnly = true)
	public AccuracyView accuracy() {
		long total = trades.count();
		long wins = trades.countByWonTrue();

		Instant since30 = Instant.now().minus(Duration.ofDays(30));
		long total30 = trades.countByCreatedAtAfter(since30);
		long wins30 = trades.countByWonTrueAndCreatedAtAfter(since30);

		List<PaperTrade> last10 = trades.findTop10ByOrderByIdDesc();
		long wins10 = last10.stream().filter(PaperTrade::isWon).count();

		GraduationSummary g = graduation.summary();
		return new AccuracyView(
				window(total, wins),
				window(total30, wins30),
				window(last10.size(), wins10),
				recommendations.count(),
				decisions.countByDecision(Decision.TAKEN),
				decisions.countByDecision(Decision.DECLINED),
				g.state(),
				g.badge());
	}

	private static WindowStat window(long trades, long wins) {
		Integer pct = trades == 0 ? null : (int) Math.round(100.0 * wins / trades);
		return new WindowStat(trades, wins, pct, trades >= MEANINGFUL_TRADES);
	}

	// ---- Story 9.3: contribution attribution ----

	@Transactional(readOnly = true)
	public AttributionView attribution() {
		List<AgentWeightAggregate> agg = signals.aggregateByAgent();
		double totalWeight = agg.stream().mapToDouble(a -> weight(a.getTotalWeight())).sum();
		int n = agg.size();
		double equalShare = n == 0 ? 0 : 100.0 / n;
		Map<String, AdaptiveTuningService.ReliabilityView> reliability = tuning.reliabilityByAgent();

		List<AgentContribution> agents = agg.stream()
				.map(a -> {
					double pct = totalWeight <= 0 ? 0 : weight(a.getTotalWeight()) / totalWeight * 100;
					// "dead weight": meaningfully below an even split (only flag once there's a panel of agents).
					boolean underperformer = n > 2 && pct < equalShare * 0.5;
					AdaptiveTuningService.ReliabilityView r = reliability.get(a.getAgent());
					Integer hitRatePct = r == null ? null : r.hitRatePct();
					int samples = r == null ? 0 : r.sampleSize();
					// Learned weight multiplier (Phase B); null when the agent has no reliability row yet.
					Double learnedMultiplier = r == null ? null : round2(r.weightMultiplier());
					return new AgentContribution(a.getAgent(), round2(pct), a.getSignalCount(), underperformer,
							hitRatePct, samples, learnedMultiplier);
				})
				.sorted((x, y) -> Double.compare(y.contributionPct(), x.contributionPct()))
				.toList();
		return new AttributionView(agents, n);
	}

	// ---- Story 9.4: calibration ----

	@Transactional(readOnly = true)
	public CalibrationView calibration() {
		List<PaperTrade> all = trades.findAll();
		List<Long> recIds = all.stream().map(PaperTrade::getRecommendationId).filter(java.util.Objects::nonNull).toList();
		Map<Long, Recommendation> recById = recommendations.findAllById(recIds).stream()
				.collect(Collectors.toMap(Recommendation::getId, Function.identity()));

		long[] count = new long[CALIBRATION_BINS];
		long[] wins = new long[CALIBRATION_BINS];
		int resolved = 0;
		double brierSum = 0;
		for (PaperTrade t : all) {
			Recommendation r = t.getRecommendationId() == null ? null : recById.get(t.getRecommendationId());
			if (r == null) {
				continue; // can't bin an outcome with no stated probability
			}
			double stated = statedProbability(r);
			int bin = binFor(stated);
			count[bin]++;
			if (t.isWon()) {
				wins[bin]++;
			}
			brierSum += Math.pow(stated - (t.isWon() ? 1.0 : 0.0), 2);
			resolved++;
		}

		List<Bin> bins = new ArrayList<>(CALIBRATION_BINS);
		for (int i = 0; i < CALIBRATION_BINS; i++) {
			Integer hit = count[i] == 0 ? null : (int) Math.round(100.0 * wins[i] / count[i]);
			bins.add(new Bin(i * 10, (i + 1) * 10, count[i], wins[i], hit, count[i] >= CALIBRATION_MIN_SAMPLE));
		}
		// Brier score: mean squared error of the stated probabilities against what happened.
		// 0 = perfectly calibrated & confident; 0.25 = always saying 50/50 (a coin flip); higher = worse.
		Double brier = resolved == 0 ? null : Math.round(brierSum / resolved * 10000.0) / 10000.0;
		return new CalibrationView(bins, resolved, CALIBRATION_MIN_SAMPLE, brier);
	}

	// ---- Regret analysis (Fable 5 follow-up: the behavioral mirror) ----

	/**
	 * How the user's Taken vs Declined calls actually played out, using the Investor's closed paper
	 * trades as the outcome record. Per recommendation the closed legs' direction-adjusted returns are
	 * averaged first (vs-SPY excess when benchmarked, absolute otherwise) so multi-horizon legs don't
	 * overweight a thesis; then the recs are bucketed by the user's decision. {@code regretGapPct} is
	 * declined-average minus taken-average — positive means "the calls you declined did better than
	 * the ones you took."
	 */
	@Transactional(readOnly = true)
	public RegretView regret() {
		List<TradeDecision> all = decisions.findAll();
		if (all.isEmpty()) {
			return new RegretView(bucket(List.of()), bucket(List.of()), null);
		}
		// recommendation → average closed-leg return (vs SPY when available).
		Map<Long, Double> avgReturnByRec = simulatedTrades.findByStatus(SimulatedTrade.Status.CLOSED).stream()
				.filter(t -> t.getRecommendationId() != null)
				.collect(Collectors.groupingBy(SimulatedTrade::getRecommendationId,
						Collectors.averagingDouble(PerformanceService::tradeReturn)));

		List<Double> taken = new ArrayList<>();
		List<Double> declined = new ArrayList<>();
		for (TradeDecision d : all) {
			Double ret = avgReturnByRec.get(d.getRecommendationId());
			if (ret == null) {
				continue; // decided, but no closed paper legs yet — nothing to score
			}
			(d.getDecision() == Decision.TAKEN ? taken : declined).add(ret);
		}
		RegretBucket takenB = bucket(taken);
		RegretBucket declinedB = bucket(declined);
		Double gap = (takenB.avgReturnPct() == null || declinedB.avgReturnPct() == null) ? null
				: round2(declinedB.avgReturnPct() - takenB.avgReturnPct());
		return new RegretView(takenB, declinedB, gap);
	}

	private static double tradeReturn(SimulatedTrade t) {
		BigDecimal r = t.getExcessReturnPct() != null ? t.getExcessReturnPct() : t.getReturnPct();
		return r == null ? 0 : r.doubleValue();
	}

	private static RegretBucket bucket(List<Double> returns) {
		if (returns.isEmpty()) {
			return new RegretBucket(0, null, null);
		}
		long wins = returns.stream().filter(r -> r > 0).count();
		double avg = returns.stream().mapToDouble(Double::doubleValue).average().orElse(0);
		return new RegretBucket(returns.size(), (int) Math.round(100.0 * wins / returns.size()), round2(avg));
	}

	/** The probability the recommendation stated for the direction it actually called. */
	private static double statedProbability(Recommendation r) {
		BigDecimal p = r.getDirection() == SignalDirection.BULLISH ? r.getBullProbability() : r.getBearProbability();
		return p == null ? 0 : p.doubleValue();
	}

	private static int binFor(double probability) {
		int bin = (int) Math.floor(probability * CALIBRATION_BINS);
		return Math.max(0, Math.min(CALIBRATION_BINS - 1, bin));
	}

	private static double weight(BigDecimal w) {
		return w == null ? 0 : w.doubleValue();
	}

	private static double round2(double v) {
		return Math.round(v * 100.0) / 100.0;
	}

	// ---- DTOs ----

	/** Story 9.2. {@code avgGains} is deliberately omitted — outcomes carry no P&L (see class javadoc). */
	public record AccuracyView(WindowStat all, WindowStat last30d, WindowStat last10,
			long totalIssued, long taken, long declined, String graduationState, String graduationBadge) {
	}

	/** Win rate over one window. {@code winRatePct} is null when there are no trades. */
	public record WindowStat(long trades, long wins, Integer winRatePct, boolean statisticallyMeaningful) {
	}

	/** Story 9.3. */
	public record AttributionView(List<AgentContribution> agents, int agentCount) {
	}

	/**
	 * One agent's share of signal weight, plus its Phase B learned reliability: realized
	 * {@code hitRatePct} over {@code reliabilitySamples} closed trades and the resulting
	 * {@code learnedMultiplier} on its weight (null until it has a reliability row).
	 */
	public record AgentContribution(String agent, double contributionPct, long signalCount,
			boolean underperformer, Integer hitRatePct, int reliabilitySamples, Double learnedMultiplier) {
	}

	/**
	 * Story 9.4 — a reliability diagram as bins, plus the Brier score of the stated probabilities
	 * (0 = perfect, 0.25 = coin flip, higher = worse); null until an outcome has resolved.
	 */
	public record CalibrationView(List<Bin> bins, int resolvedCount, int minSampleSize, Double brierScore) {
	}

	/** One decision bucket: how many decided recs have closed paper legs, and how they went. */
	public record RegretBucket(int count, Integer winRatePct, Double avgReturnPct) {
	}

	/** The behavioral mirror: taken vs declined outcomes and the gap between them. */
	public record RegretView(RegretBucket taken, RegretBucket declined, Double regretGapPct) {
	}

	/** One probability bin {@code [lowPct, highPct)} with the actual hit rate seen. */
	public record Bin(int lowPct, int highPct, long count, long wins, Integer actualHitRatePct, boolean sufficient) {
	}
}
