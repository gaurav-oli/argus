package com.argus.recommendation;

import com.argus.model.ModelGateway;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The Analyst Logic Review — an automated "LLM proposes, data disposes" refinement of the recommender.
 * On a schedule (after the nightly adaptive-tuning run) the local model reviews the closed paper-trade
 * record and proposes per-agent weight-multiplier <em>factors</em> (e.g. "trust social 15% less"). The
 * agent then <b>backtests</b> those factors by re-scoring every closed trade through the real
 * {@link ProbabilityScoringEngine} and only adopts them if they lower the Brier score (better-calibrated)
 * without hurting directional accuracy, over a minimum sample. So the LLM widens the space of ideas;
 * realized historical outcomes — not the model's say-so — decide what ships. Adopted factors multiply
 * into the live per-agent multipliers (clamped to the tuning bounds) and the change is fully reversible
 * (the next tuning recompute re-derives the base from outcomes). Every run is logged to
 * {@code logic_review}, adopted or not.
 */
@Service
public class LogicReviewService {

	private static final Logger log = LoggerFactory.getLogger(LogicReviewService.class);
	private static final JsonMapper JSON = JsonMapper.builder().build();

	private final SimulatedTradeRepository trades;
	private final RecommendationRepository recommendations;
	private final AgentReliabilityRepository reliabilities;
	private final AdaptiveTuningService tuning;
	private final ProbabilityScoringEngine engine;
	private final ModelGateway gateway;
	private final JdbcTemplate jdbc;
	private final LogicReviewProperties props;
	private final AdaptiveTuningProperties tuningProps;

	public LogicReviewService(SimulatedTradeRepository trades, RecommendationRepository recommendations,
			AgentReliabilityRepository reliabilities, AdaptiveTuningService tuning, ProbabilityScoringEngine engine,
			ModelGateway gateway, JdbcTemplate jdbc, LogicReviewProperties props,
			AdaptiveTuningProperties tuningProps) {
		this.trades = trades;
		this.recommendations = recommendations;
		this.reliabilities = reliabilities;
		this.tuning = tuning;
		this.engine = engine;
		this.gateway = gateway;
		this.jdbc = jdbc;
		this.props = props;
		this.tuningProps = tuningProps;
	}

	public record Proposal(String agent, double factor, String why) {
	}

	public record Result(boolean ran, boolean adopted, int sampleSize, Double beforeBrier, Double afterBrier,
			Double beforeAccuracy, Double afterAccuracy, String reason, List<Proposal> proposals) {
	}

	/** After the nightly tuning recompute (02:30), refine on top of the freshly-derived multipliers. */
	@Scheduled(cron = "${argus.logic-review.cron:0 0 3 * * *}")
	public void scheduledReview() {
		try {
			review();
		}
		catch (RuntimeException ex) {
			log.warn("Analyst logic review failed: {}", ex.getMessage());
		}
	}

	/** One review pass: propose (LLM) → backtest (deterministic) → adopt iff it beats current. */
	@Transactional
	public Result review() {
		if (!props.enabled()) {
			return persist(new Result(false, false, 0, null, null, null, null, "Logic review disabled.", List.of()),
					"n/a");
		}

		// Build the evaluable, labelled backtest set: closed trades with a directional call + outcome.
		List<Eval> evals = new ArrayList<>();
		Map<Long, Recommendation> recById = new HashMap<>();
		for (SimulatedTrade t : trades.findByStatus(SimulatedTrade.Status.CLOSED)) {
			if (t.getWon() == null || t.getRecommendationId() == null) {
				continue;
			}
			Recommendation r = recById.computeIfAbsent(t.getRecommendationId(),
					id -> recommendations.findWithSignalsById(id).orElse(null));
			if (r == null || r.getDirection() == SignalDirection.NEUTRAL) {
				continue;
			}
			evals.add(new Eval(r, winningDirection(r.getDirection(), t.getWon())));
		}

		if (evals.size() < props.minSample()) {
			String reason = "Not enough closed trades yet (%d < %d) — keeping current logic."
					.formatted(evals.size(), props.minSample());
			return persist(new Result(true, false, evals.size(), null, null, null, null, reason, List.of()), "n/a");
		}

		Map<String, Double> currentMult = currentMultipliers();
		Score baseline = backtest(evals, Map.of()); // factor 1.0 for all = the calls as history made them
		List<Proposal> proposals = propose(evals, currentMult, baseline);
		Map<String, Double> factors = new HashMap<>();
		proposals.forEach(p -> factors.put(p.agent(), p.factor()));
		Score proposed = backtest(evals, factors);

		boolean improves = proposed.brier() <= baseline.brier() - props.brierMargin()
				&& proposed.accuracy() >= baseline.accuracy();
		boolean adopt = improves && props.autoApply() && !factors.isEmpty();

		String reason;
		if (factors.isEmpty()) {
			reason = "Model proposed no actionable changes — keeping current logic.";
		}
		else if (!improves) {
			reason = "Proposed changes didn't beat current on the backtest (Brier %.4f → %.4f) — rejected."
					.formatted(baseline.brier(), proposed.brier());
		}
		else if (!props.autoApply()) {
			reason = "Proposed changes improve the backtest (Brier %.4f → %.4f) but auto-apply is off — logged only."
					.formatted(baseline.brier(), proposed.brier());
		}
		else {
			apply(factors, currentMult);
			reason = "Adopted: backtest improved (Brier %.4f → %.4f, accuracy %.1f%% → %.1f%%) over %d trades."
					.formatted(baseline.brier(), proposed.brier(), baseline.accuracy() * 100,
							proposed.accuracy() * 100, evals.size());
		}

		Result result = new Result(true, adopt, evals.size(), baseline.brier(), proposed.brier(),
				baseline.accuracy(), proposed.accuracy(), reason, proposals);
		log.info("Analyst logic review: {}", reason);
		return persist(result, "gemma", dissentStats(evals));
	}

	// ---- dissent record (Fable 5 review item 8: agents auditing each other through outcomes) ----

	/**
	 * Per-agent dissent record over the closed-trade set: how often an agent's non-neutral signal
	 * pointed <em>against</em> the direction the Analyst ultimately called, and how often that dissent
	 * turned out to be right (the trade lost). A consistently-right dissenter is under-weighted; a
	 * consistently-wrong one is noise — either way the LLM reviewer should see it, and the backtest
	 * still decides. Returns agent → [dissents, dissentsRight].
	 */
	static Map<String, int[]> dissentStats(List<Eval> evals) {
		Map<String, int[]> out = new LinkedHashMap<>();
		for (Eval e : evals) {
			SignalDirection called = e.rec().getDirection();
			for (RecommendationSignal s : e.rec().getSignals()) {
				if (s.getDirection() == SignalDirection.NEUTRAL || s.getDirection() == called) {
					continue;
				}
				int[] c = out.computeIfAbsent(s.getAgent(), k -> new int[2]);
				c[0]++;
				if (s.getDirection() == e.actual()) {
					c[1]++; // the call went the dissenter's way — the fleet missed what it saw
				}
			}
		}
		return out;
	}

	private static String dissentSection(Map<String, int[]> dissent) {
		if (dissent.isEmpty()) {
			return "";
		}
		StringBuilder sb = new StringBuilder("\nDISSENT RECORD (times an agent disagreed with the final call, and how often the dissenter was right):\n");
		dissent.forEach((agent, c) -> sb.append("- ").append(agent)
				.append(": dissented ").append(c[0]).append(" time(s), right ").append(c[1])
				.append(" time(s) (").append(Math.round(100.0 * c[1] / c[0])).append("%)\n"));
		return sb.toString();
	}

	// ---- LLM proposal ----

	private List<Proposal> propose(List<Eval> evals, Map<String, Double> currentMult, Score baseline) {
		Map<String, int[]> tally = new HashMap<>(); // agent → [contributed, agreedWithOutcome]
		for (Eval e : evals) {
			for (RecommendationSignal s : e.rec().getSignals()) {
				if (s.getDirection() == SignalDirection.NEUTRAL) {
					continue;
				}
				int[] c = tally.computeIfAbsent(s.getAgent(), k -> new int[2]);
				c[0]++;
				if (s.getDirection() == e.actual()) {
					c[1]++;
				}
			}
		}
		StringBuilder stats = new StringBuilder();
		tally.forEach((agent, c) -> stats.append("- ").append(agent)
				.append(": contributed to ").append(c[0]).append(" trades, agreed with outcome ")
				.append(c[1]).append(" (hit rate ").append(Math.round(100.0 * c[1] / c[0])).append("%), ")
				.append("current multiplier ").append(String.format(Locale.ROOT, "%.2f", currentMult.getOrDefault(agent, 1.0)))
				.append('\n'));

		String prompt = """
				You are the Analyst's self-tuning reviewer. Below is each signal agent's historical \
				agreement with what trades actually did. Propose a weight-multiplier FACTOR per agent to \
				improve future calls: >1 to trust an agent more, <1 to trust it less, near 1 to leave it. \
				Only suggest a change where the record justifies it. A dissenter that was repeatedly RIGHT \
				against the final call is under-weighted; one repeatedly wrong is noise. Factors must be \
				between %.2f and %.2f.

				AGENT PERFORMANCE (%d closed trades, current Brier %.4f):
				%s%s
				Respond with ONLY a JSON array, no prose: [{"agent":"agent-2-social","factor":0.85,"why":"short reason"}]
				"""
				.formatted(props.factorMin(), props.factorMax(), evals.size(), baseline.brier(), stats,
						dissentSection(dissentStats(evals)));

		try {
			return parse(gateway.generate(prompt));
		}
		catch (RuntimeException ex) {
			log.warn("Logic-review model call failed ({}) — proposing no change", ex.getMessage());
			return List.of();
		}
	}

	private List<Proposal> parse(String raw) {
		if (raw == null) {
			return List.of();
		}
		String s = raw.replace("```json", "").replace("```", "").strip();
		int lb = s.indexOf('['), rb = s.lastIndexOf(']');
		if (lb < 0 || rb <= lb) {
			return List.of();
		}
		List<Proposal> out = new ArrayList<>();
		try {
			for (JsonNode n : JSON.readTree(s.substring(lb, rb + 1))) {
				String agent = n.path("agent").asString("").trim();
				double factor = n.path("factor").asDouble(1.0);
				if (agent.isEmpty() || Double.isNaN(factor)) {
					continue;
				}
				double clamped = Math.max(props.factorMin(), Math.min(props.factorMax(), factor));
				if (Math.abs(clamped - 1.0) < 1e-6) {
					continue; // no-op suggestion
				}
				out.add(new Proposal(agent, round2(clamped), n.path("why").asString("").trim()));
			}
		}
		catch (RuntimeException ex) {
			log.warn("Logic-review JSON parse failed: {}", ex.getMessage());
			return List.of();
		}
		return out;
	}

	// ---- deterministic backtest ----

	private Score backtest(List<Eval> evals, Map<String, Double> factors) {
		int correct = 0;
		double brierSum = 0;
		for (Eval e : evals) {
			List<AgentSignal> signals = new ArrayList<>();
			for (RecommendationSignal s : e.rec().getSignals()) {
				double f = factors.getOrDefault(s.getAgent(), 1.0);
				signals.add(new AgentSignal(s.getAgent(), s.getDirection(), s.getWeight().doubleValue() * f, ""));
			}
			double bull = engine.score(signals).bullProbability();
			SignalDirection predicted = bull >= 0.5 ? SignalDirection.BULLISH : SignalDirection.BEARISH;
			if (predicted == e.actual()) {
				correct++;
			}
			double actualBull = e.actual() == SignalDirection.BULLISH ? 1.0 : 0.0;
			brierSum += (bull - actualBull) * (bull - actualBull);
		}
		int n = evals.size();
		return new Score((double) correct / n, brierSum / n);
	}

	// ---- apply (only when the backtest approved) ----

	private void apply(Map<String, Double> factors, Map<String, Double> currentMult) {
		factors.forEach((agent, factor) -> {
			double base = currentMult.getOrDefault(agent, 1.0);
			double refined = clamp(base * factor, tuningProps.weightMin(), tuningProps.weightMax());
			AgentReliability row = reliabilities.findById(agent).orElseGet(() -> new AgentReliability(agent));
			Double hitRate = row.getHitRate() == null ? null : row.getHitRate().doubleValue();
			row.update(row.getSampleSize(), hitRate, refined);
			reliabilities.save(row);
		});
		tuning.loadCache(); // refresh the hot-path multiplier cache so the change is live immediately
	}

	private Map<String, Double> currentMultipliers() {
		Map<String, Double> m = new HashMap<>();
		reliabilities.findAll().forEach(r -> m.put(r.getAgent(), r.getWeightMultiplier().doubleValue()));
		return m;
	}

	// ---- persistence + helpers ----

	private Result persist(Result r, String model) {
		return persist(r, model, Map.of());
	}

	private Result persist(Result r, String model, Map<String, int[]> dissent) {
		List<Map<String, Object>> proposalsJson = new ArrayList<>();
		for (Proposal p : r.proposals()) {
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("agent", p.agent());
			m.put("factor", p.factor());
			m.put("why", p.why());
			proposalsJson.add(m);
		}
		Map<String, Object> dissentJson = new LinkedHashMap<>();
		dissent.forEach((agent, c) -> dissentJson.put(agent,
				Map.of("dissents", c[0], "right", c[1])));
		jdbc.update("insert into logic_review (ran_at, model, sample_size, before_brier, after_brier,"
				+ " before_accuracy, after_accuracy, adopted, reason, proposals, dissent)"
				+ " values (?, ?, ?, ?, ?, ?, ?, ?, ?, cast(? as jsonb), cast(? as jsonb))",
				Timestamp.from(java.time.Instant.now()), model, r.sampleSize(), r.beforeBrier(), r.afterBrier(),
				r.beforeAccuracy(), r.afterAccuracy(), r.adopted(), r.reason(),
				JSON.writeValueAsString(proposalsJson), JSON.writeValueAsString(dissentJson));
		return r;
	}

	private static SignalDirection winningDirection(SignalDirection called, boolean won) {
		if (won) {
			return called;
		}
		return called == SignalDirection.BULLISH ? SignalDirection.BEARISH : SignalDirection.BULLISH;
	}

	private static double clamp(double v, double lo, double hi) {
		return Math.max(lo, Math.min(hi, v));
	}

	private static double round2(double v) {
		return Math.round(v * 100.0) / 100.0;
	}

	/** A closed trade's recommendation plus the direction the market actually went (package-visible for tests). */
	record Eval(Recommendation rec, SignalDirection actual) {
	}

	private record Score(double accuracy, double brier) {
	}
}
