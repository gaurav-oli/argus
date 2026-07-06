package com.argus.recommendation;

import jakarta.annotation.PostConstruct;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The Analyst's learning layer (Phase B, FR-11 follow-up). On a schedule it reads the Investor's
 * closed paper trades ({@link SimulatedTrade}) and derives two reversible adjustments that feed the
 * next recommendation:
 *
 * <ul>
 *   <li><b>Per-agent weight multiplier</b> — from how often each agent's directional signal agreed with
 *       what the trade actually did (its realized hit rate), shrunk toward 1.0 by sample size and
 *       clamped. Applied to that agent's signal weight in {@link AgentSignalGatherer}.</li>
 *   <li><b>Isotonic probability calibration</b> — a monotone map from stated directional probability to
 *       realized hit rate (weighted pool-adjacent-violators), applied to the probability in
 *       {@link RecommendationService}.</li>
 * </ul>
 *
 * <p>Everything is deterministic (no LLM) and reversible: the pure {@link ProbabilityScoringEngine} is
 * untouched, base heuristics are never rewritten, and {@code argus.adaptive-tuning.enabled=false} makes
 * every output the identity. Cold-start safety (a minimum sample plus shrinkage) keeps the wide clamps
 * from overreacting to a handful of early trades.
 */
@Service
public class AdaptiveTuningService {

	private static final Logger log = LoggerFactory.getLogger(AdaptiveTuningService.class);
	private static final int BINS = 10; // 10-point bins; directional prob is always ≥0.5 so only 5..9 fill

	private final SimulatedTradeRepository trades;
	private final RecommendationRepository recommendations;
	private final AgentReliabilityRepository reliabilities;
	private final ProbabilityCalibrationRepository calibrations;
	private final AdaptiveTuningProperties props;

	// In-memory read caches, refreshed at startup and after each recompute.
	private final Map<String, Double> weightMultipliers = new ConcurrentHashMap<>();
	private volatile double[] calibratedByBin = identityBins();

	public AdaptiveTuningService(SimulatedTradeRepository trades, RecommendationRepository recommendations,
			AgentReliabilityRepository reliabilities, ProbabilityCalibrationRepository calibrations,
			AdaptiveTuningProperties props) {
		this.trades = trades;
		this.recommendations = recommendations;
		this.reliabilities = reliabilities;
		this.calibrations = calibrations;
		this.props = props;
	}

	@PostConstruct
	void loadCache() {
		reliabilities.findAll().forEach(r -> weightMultipliers.put(r.getAgent(), r.getWeightMultiplier().doubleValue()));
		double[] bins = identityBins();
		calibrations.findAll().forEach(b -> {
			if (b.getCalibrated() != null) {
				bins[binIndex(b.getBinLow() / 100.0)] = b.getCalibrated().doubleValue();
			}
		});
		calibratedByBin = bins;
	}

	// ---- Read side (hot path — no DB) ----

	/** Learned weight multiplier for an agent; 1.0 when disabled or unseen. */
	public double weightMultiplier(String agent) {
		if (!props.enabled()) {
			return 1.0;
		}
		return weightMultipliers.getOrDefault(agent, 1.0);
	}

	/**
	 * Calibrate a stated directional probability (the probability of the called direction, always ≥0.5)
	 * toward the realized hit rate. Floored at 0.5 so calibration never flips the call — an
	 * over-confident-and-wrong band collapses toward a coin flip rather than reversing direction.
	 */
	public double calibrateDirectionalProbability(double statedDirectional) {
		if (!props.enabled()) {
			return statedDirectional;
		}
		double[] bins = calibratedByBin;
		int bin = binIndex(statedDirectional);
		Double mapped = null;
		for (int i = bin; i >= 0; i--) { // nearest populated bin at or below
			if (!Double.isNaN(bins[i])) {
				mapped = bins[i];
				break;
			}
		}
		if (mapped == null) {
			return statedDirectional; // no calibration data yet → identity
		}
		return Math.max(0.5, Math.min(1.0, mapped));
	}

	/** Per-agent reliability for the ops UI (hit rate + sample + learned multiplier). */
	@Transactional(readOnly = true)
	public Map<String, ReliabilityView> reliabilityByAgent() {
		Map<String, ReliabilityView> out = new HashMap<>();
		reliabilities.findAll().forEach(r -> out.put(r.getAgent(), new ReliabilityView(
				r.getAgent(),
				r.getSampleSize(),
				r.getHitRate() == null ? null : (int) Math.round(r.getHitRate().doubleValue() * 100),
				r.getWeightMultiplier().doubleValue())));
		return out;
	}

	public record ReliabilityView(String agent, int sampleSize, Integer hitRatePct, double weightMultiplier) {
	}

	// ---- Recompute (scheduled) ----

	/** Nightly: re-derive multipliers + calibration from the closed paper-trade record. */
	@Scheduled(cron = "${argus.adaptive-tuning.recompute-cron:0 30 2 * * *}")
	@Transactional
	public void recompute() {
		List<SimulatedTrade> closed = trades.findByStatus(SimulatedTrade.Status.CLOSED);
		if (closed.isEmpty()) {
			return;
		}
		// Load each trade's recommendation (with signals) once.
		Map<Long, Recommendation> recById = new HashMap<>();
		for (SimulatedTrade t : closed) {
			Long id = t.getRecommendationId();
			if (id != null && !recById.containsKey(id)) {
				recommendations.findWithSignalsById(id).ifPresent(r -> recById.put(id, r));
			}
		}

		recomputeAgentWeights(closed, recById);
		recomputeCalibration(closed, recById);
		loadCache();
		log.info("Adaptive tuning recomputed from {} closed trade(s); enabled={}", closed.size(), props.enabled());
	}

	private void recomputeAgentWeights(List<SimulatedTrade> closed, Map<Long, Recommendation> recById) {
		Map<String, int[]> tally = new HashMap<>(); // agent → [contributed, correct]
		for (SimulatedTrade t : closed) {
			Recommendation r = recById.get(t.getRecommendationId());
			if (r == null) {
				continue;
			}
			SignalDirection winning = Boolean.TRUE.equals(t.getWon())
					? r.getDirection() : opposite(r.getDirection());
			for (RecommendationSignal s : r.getSignals()) {
				if (s.getDirection() == SignalDirection.NEUTRAL) {
					continue;
				}
				int[] c = tally.computeIfAbsent(s.getAgent(), k -> new int[2]);
				c[0]++;
				if (s.getDirection() == winning) {
					c[1]++;
				}
			}
		}
		tally.forEach((agent, c) -> {
			int n = c[0];
			double hitRate = (double) c[1] / n;
			double raw = 1.0 + props.weightGain() * (hitRate - 0.5);
			double mult = shrinkAndClamp(raw, n, props.weightMin(), props.weightMax());
			AgentReliability row = reliabilities.findById(agent).orElseGet(() -> new AgentReliability(agent));
			row.update(n, hitRate, mult);
			reliabilities.save(row);
		});
	}

	private void recomputeCalibration(List<SimulatedTrade> closed, Map<Long, Recommendation> recById) {
		long[] count = new long[BINS];
		long[] wins = new long[BINS];
		int total = 0;
		for (SimulatedTrade t : closed) {
			Recommendation r = recById.get(t.getRecommendationId());
			if (r == null) {
				continue;
			}
			int bin = binIndex(statedDirectional(r));
			count[bin]++;
			if (Boolean.TRUE.equals(t.getWon())) {
				wins[bin]++;
			}
			total++;
		}

		// Cold-start: below the minimum total, publish identity (no calibration yet).
		double[] calibrated = new double[BINS];
		double[] rawHit = new double[BINS];
		java.util.Arrays.fill(calibrated, Double.NaN);
		java.util.Arrays.fill(rawHit, Double.NaN);
		if (total >= props.minSample()) {
			List<Integer> filled = new ArrayList<>();
			List<Double> values = new ArrayList<>();
			List<Double> weights = new ArrayList<>();
			for (int i = 0; i < BINS; i++) {
				if (count[i] > 0) {
					rawHit[i] = (double) wins[i] / count[i];
					filled.add(i);
					values.add(rawHit[i]);
					weights.add((double) count[i]);
				}
			}
			double[] monotone = pav(values, weights); // weighted isotonic (non-decreasing)
			for (int k = 0; k < filled.size(); k++) {
				calibrated[filled.get(k)] = monotone[k];
			}
		}

		for (int i = 0; i < BINS; i++) {
			int low = i * 10;
			ProbabilityCalibrationBin bin = calibrations.findById((short) low)
					.orElseGet(() -> new ProbabilityCalibrationBin(low));
			bin.update((int) count[i],
					Double.isNaN(rawHit[i]) ? null : rawHit[i],
					Double.isNaN(calibrated[i]) ? null : calibrated[i]);
			calibrations.save(bin);
		}
	}

	// ---- math ----

	/** Shrink {@code raw} toward 1.0 by n/(n+K), zero adjustment below minSample, then clamp. */
	private double shrinkAndClamp(double raw, int n, double lo, double hi) {
		if (n < props.minSample()) {
			return 1.0;
		}
		double shrunk = 1.0 + (raw - 1.0) * (n / (n + props.shrinkK()));
		return Math.max(lo, Math.min(hi, shrunk));
	}

	/** Weighted pool-adjacent-violators: nearest non-decreasing fit minimizing weighted squared error. */
	static double[] pav(List<Double> values, List<Double> weights) {
		int m = values.size();
		double[] v = new double[m];
		double[] w = new double[m];
		int[] len = new int[m];
		int top = -1;
		for (int i = 0; i < m; i++) {
			double cv = values.get(i);
			double cw = weights.get(i);
			int cl = 1;
			while (top >= 0 && v[top] >= cv) { // merge while previous block violates monotonicity
				double mergedW = w[top] + cw;
				cv = (v[top] * w[top] + cv * cw) / mergedW;
				cw = mergedW;
				cl += len[top];
				top--;
			}
			top++;
			v[top] = cv;
			w[top] = cw;
			len[top] = cl;
		}
		double[] out = new double[m];
		int idx = 0;
		for (int b = 0; b <= top; b++) {
			for (int j = 0; j < len[b]; j++) {
				out[idx++] = v[b];
			}
		}
		return out;
	}

	/** The probability the recommendation stated for the direction it actually called (always ≥0.5). */
	private static double statedDirectional(Recommendation r) {
		BigDecimal p = r.getDirection() == SignalDirection.BEARISH ? r.getBearProbability() : r.getBullProbability();
		return p == null ? 0.5 : p.doubleValue();
	}

	private static SignalDirection opposite(SignalDirection d) {
		return d == SignalDirection.BULLISH ? SignalDirection.BEARISH
				: d == SignalDirection.BEARISH ? SignalDirection.BULLISH : SignalDirection.NEUTRAL;
	}

	private static int binIndex(double prob) {
		return Math.max(0, Math.min(BINS - 1, (int) Math.floor(prob * BINS)));
	}

	private static double[] identityBins() {
		double[] b = new double[BINS];
		java.util.Arrays.fill(b, Double.NaN);
		return b;
	}
}
