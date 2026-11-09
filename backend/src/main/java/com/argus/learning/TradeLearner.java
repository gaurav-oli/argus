package com.argus.learning;

import com.argus.learning.LessonMiner.Candidate;
import com.argus.learning.LessonMiner.Observation;
import com.argus.learning.LessonMiner.Result;
import com.argus.model.ClaudeFeature;
import com.argus.model.LenientJsonParser;
import com.argus.model.ModelGateway;
import com.argus.model.ModelTier;
import com.argus.recommendation.Recommendation;
import com.argus.recommendation.RecommendationRepository;
import com.argus.recommendation.RecommendationSignalRepository;
import com.argus.recommendation.SimulatedTrade;
import com.argus.recommendation.SimulatedTradeRepository;
import com.argus.regime.SectorClassifier;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

/**
 * Agent 13 — the Trade Learner. It studies the paper book's wins and losses, finds the patterns behind them,
 * and turns the ones that survive scrutiny into rules that change how the whole system behaves next time.
 *
 * <p>The division of labour is deliberate — <b>code finds and validates, the model explains</b>:
 * <ol>
 *   <li>{@link LessonMiner} (deterministic) finds situations that reliably do worse or better than the rest,
 *       over independent bets, and requires each to replicate on newer held-out trades.</li>
 *   <li>Only a replicated pattern becomes an {@code ACTIVE} {@link LearnedRule}; rules that stop holding are
 *       retired on the next run. Everything is recorded with the numbers that justify it.</li>
 *   <li>The local model then writes a hypothesis for <em>why</em> each new rule might hold, plus a plain-English
 *       narrative of what is winning and losing — informational only: the model can never create, alter or
 *       activate a rule.</li>
 * </ol>
 * Active rules are consumed through {@link Lessons} by the recommender (score adjustments and blocks), the paper
 * Investor (blocks and position size), and Agent 11 (its prompts and its guardrails) — so what is learned here
 * changes the next call, the next trade and the next deep analysis.
 */
@Service
public class TradeLearner {

	private static final Logger log = LoggerFactory.getLogger(TradeLearner.class);
	/** A rule is retired when, over all data, its cohort is no longer meaningfully different from the rest. */
	private static final double RETIRE_DELTA_PTS = 0.5;
	private static final int RETIRE_MIN_CLUSTERS = 6;

	private final SimulatedTradeRepository trades;
	private final RecommendationRepository recommendations;
	private final RecommendationSignalRepository signals;
	private final SectorClassifier sectors;
	private final LearnedRuleRepository rules;
	private final LearningReportRepository reports;
	private final LessonBook book;
	private final ModelGateway gateway;
	private final LearningProperties props;

	public TradeLearner(SimulatedTradeRepository trades, RecommendationRepository recommendations,
			RecommendationSignalRepository signals, SectorClassifier sectors, LearnedRuleRepository rules,
			LearningReportRepository reports, LessonBook book, ModelGateway gateway, LearningProperties props) {
		this.trades = trades;
		this.recommendations = recommendations;
		this.signals = signals;
		this.sectors = sectors;
		this.rules = rules;
		this.reports = reports;
		this.book = book;
		this.gateway = gateway;
		this.props = props;
	}

	/** After adaptive tuning (02:30) and the logic review (03:00), so it learns from the freshest closed trades. */
	@Scheduled(cron = "${argus.learning.cron:0 30 3 * * *}", zone = "America/Toronto")
	public void nightly() {
		if (!props.enabled()) {
			return;
		}
		try {
			run();
		}
		catch (RuntimeException ex) {
			log.warn("Trade learner run failed: {}", ex.getMessage());
		}
	}

	/** One full learning pass. Public so the UI ("Learn now") and tests can drive it. */
	public LearningReport run() {
		List<Observation> observations = observations();
		Result result = LessonMiner.mine(observations, LessonMiner.Config.defaults());

		List<LearnedRule> newlyActive = new ArrayList<>();
		if (result.skipped() == null) {
			for (Candidate c : result.candidates()) {
				LearnedRule r = upsert(c);
				if (c.decision() == LessonMiner.Decision.ACTIVATE) {
					newlyActive.add(r);
				}
			}
			retireStale(observations, newlyActive);
			reviewProbation(observations);
			book.reload();
		}

		String model = "deterministic";
		String narrative = result.skipped();
		if (result.skipped() == null && props.explainWithModel()) {
			Optional<String> written = explain(result, observations, newlyActive);
			if (written.isPresent()) {
				narrative = written.get();
				model = "local model (explanations only)";
			}
		}
		LearningReport report = reports.save(new LearningReport(result.totalTrades(), result.totalClusters(), result.baselineWin(),
				result.baselineMean(), cohortLines(result.worstCohorts(), "lost"), cohortLines(result.bestCohorts(), "earned"), narrative, model));
		log.info("Trade learner: {} trades / {} independent bets analysed; {} rule(s) newly active; {}", result.totalTrades(),
				result.totalClusters(), newlyActive.size(), result.skipped() == null ? "done" : result.skipped());
		return report;
	}

	// ---- assembling the evidence ----

	private List<Observation> observations() {
		List<SimulatedTrade> closed = trades.findByStatusOrderByClosedAtDesc(SimulatedTrade.Status.CLOSED);
		List<Long> recIds = closed.stream().map(SimulatedTrade::getRecommendationId).filter(java.util.Objects::nonNull).distinct().toList();
		Map<Long, Recommendation> recById = recIds.isEmpty() ? Map.of()
				: recommendations.findAllById(recIds).stream().collect(Collectors.toMap(Recommendation::getId, r -> r));
		Map<Long, List<LegacyTokens.Sig>> sigsByRec = new HashMap<>();
		if (!recIds.isEmpty()) {
			for (Object[] row : signals.rowsFor(recIds)) {
				sigsByRec.computeIfAbsent(((Number) row[0]).longValue(), k -> new ArrayList<>())
						.add(new LegacyTokens.Sig(String.valueOf(row[1]), String.valueOf(row[2]), ((Number) row[3]).doubleValue()));
			}
		}
		List<Observation> out = new ArrayList<>();
		for (SimulatedTrade t : closed) {
			if (t.getReturnPct() == null) continue;
			Recommendation rec = t.getRecommendationId() == null ? null : recById.get(t.getRecommendationId());
			Set<String> tokens = rec != null && rec.getFeatures() != null && !rec.getFeatures().isBlank()
					? new java.util.LinkedHashSet<>(FeatureTokens.fromJson(rec.getFeatures()))
					: LegacyTokens.build(t.getDirection().name(), sectors.sectorOf(t.getTicker()).name(), t.getHorizonDays(), t.getTicker(),
							t.getEntryPrice().doubleValue(), sigsByRec.getOrDefault(t.getRecommendationId(), List.of()));
			tokens.removeIf(x -> x.startsWith("hold="));
			tokens.add(FeatureTokens.horizonToken(t.getHorizonDays()));
			BigDecimal excess = t.getExcessReturnPct() != null ? t.getExcessReturnPct() : t.getReturnPct();
			out.add(new Observation(t.getId(), t.getTicker(), t.getDirection().name(), t.getEntryAt(), tokens, excess.doubleValue()));
		}
		out.addAll(shadowObservations());
		return out;
	}

	/**
	 * Strategy rebuild, Phase 2: closed shadow trades are evidence too — far more of them than real trades. A shadow
	 * trade whose call also became a real trade is skipped so the same bet isn't counted twice; lookalikes are pooled
	 * into independent clusters by the miner as for real trades. Ids are negated so they can't collide with real ones.
	 */
	List<Observation> shadowObservations() {
		if (jdbc == null) {
			return List.of();
		}
		List<Observation> out = new ArrayList<>();
		jdbc.query("""
				select s.id, s.ticker, s.direction, s.entry_at, s.hold_days, s.excess_return_pct, r.features
				from shadow_trades s join recommendations r on r.id = s.recommendation_id
				where s.status = 'CLOSED' and s.excess_return_pct is not null and r.features is not null
				  and not exists (select 1 from simulated_trades t where t.recommendation_id = s.recommendation_id)""", rs -> {
			Set<String> tokens = new java.util.LinkedHashSet<>(FeatureTokens.fromJson(rs.getString("features")));
			tokens.removeIf(x -> x.startsWith("hold="));
			tokens.add(FeatureTokens.horizonToken(rs.getInt("hold_days")));
			out.add(new Observation(-rs.getLong("id"), rs.getString("ticker"), rs.getString("direction"),
					rs.getTimestamp("entry_at").toInstant(), tokens, rs.getDouble("excess_return_pct")));
		});
		return out;
	}

	/** Fresh-trade evidence a rule on probation needs before it may go live, and how long it may wait for it. */
	static final java.time.Duration PROBATION_MIN_AGE = java.time.Duration.ofDays(21);
	static final java.time.Duration PROBATION_MAX_AGE = java.time.Duration.ofDays(90);
	static final int PROBATION_MIN_CLUSTERS = 5;

	/**
	 * Promote or reject rules on probation using only trades entered after probation began — data the rule has never
	 * seen. It goes ACTIVE if the pattern still holds the same way it did on the hold-out; it's rejected if it doesn't,
	 * or if not enough fresh evidence arrives within {@link #PROBATION_MAX_AGE}.
	 */
	void reviewProbation(List<Observation> observations) {
		java.time.Instant now = java.time.Instant.now();
		for (LearnedRule r : rules.findByStatusOrderByActivatedAtDesc(LearnedRule.Status.PROBATION)) {
			java.time.Instant since = r.getProbationStartedAt() == null ? now : r.getProbationStartedAt();
			if (now.isBefore(since.plus(PROBATION_MIN_AGE))) continue;
			List<Observation> fresh = observations.stream().filter(o -> o.entryAt() != null && o.entryAt().isAfter(since)).toList();
			LessonMiner.Stats s = LessonMiner.recheck(fresh, r.predicateSet());
			if (s.clusters() < PROBATION_MIN_CLUSTERS) {
				if (now.isAfter(since.plus(PROBATION_MAX_AGE))) {
					r.reject(String.format(Locale.ROOT, "Probation ended without enough fresh evidence (%d of %d independent bets).",
							s.clusters(), PROBATION_MIN_CLUSTERS));
					rules.save(r);
				}
				continue;
			}
			boolean holds = switch (r.getKind()) {
				case BOOST -> s.delta() >= RETIRE_DELTA_PTS && s.meanExcess() > 0;
				default -> s.delta() <= -RETIRE_DELTA_PTS && s.meanExcess() < 0;
			};
			if (holds) {
				r.activate(String.format(Locale.ROOT, "Confirmed on probation: %d fresh bets averaged %+.1f%% vs %+.1f%% for the rest.",
						s.clusters(), s.meanExcess(), s.complementMean()));
				log.info("Trade learner: rule {} passed probation ({})", r.getId(), r.getDescription());
			}
			else {
				r.reject(String.format(Locale.ROOT, "Failed probation: %d fresh bets averaged %+.1f%% vs %+.1f%% for the rest.",
						s.clusters(), s.meanExcess(), s.complementMean()));
				log.info("Trade learner: rule {} failed probation ({})", r.getId(), r.getDescription());
			}
			rules.save(r);
		}
	}

	private org.springframework.jdbc.core.JdbcTemplate jdbc;

	@org.springframework.beans.factory.annotation.Autowired(required = false)
	void setJdbc(org.springframework.jdbc.core.JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	// ---- rules ----

	private LearnedRule upsert(Candidate c) {
		LearnedRule.Kind kind = c.kind();
		String preds = String.join(",", new java.util.TreeSet<>(c.predicates()));
		LearnedRule rule = rules.findAll().stream().filter(r -> r.getKind() == kind && r.getPredicates().equals(preds)).findFirst()
				.orElseGet(() -> new LearnedRule(kind, c.predicates(), "", c.effect()));
		rule.describe(RuleText.describe(c.predicates(), c.fit().meanExcess(), c.fit().clusters(), c.fit().winRate()));
		rule.recordEvidence(c.fit().trades(), c.fit().clusters(), c.fit().winRate(), c.fit().meanExcess(), c.holdoutClusters(), c.holdoutMean());
		if (c.decision() == LessonMiner.Decision.ACTIVATE) {
			// Strategy rebuild, Phase 2: replicating on the hold-out isn't enough — it must also hold on fresh trades.
			if (rule.getStatus() != LearnedRule.Status.ACTIVE) {
				rule.startProbation(c.note());
			}
		}
		else if (rule.getStatus() != LearnedRule.Status.ACTIVE && rule.getStatus() != LearnedRule.Status.PROBATION) {
			rule.reject(c.note());
		}
		LearnedRule saved = rules.save(rule);
		// A severe cohort also shrinks the position taken on it, not just the conviction.
		if (c.decision() == LessonMiner.Decision.ACTIVATE && c.kind() == LearnedRule.Kind.PENALTY && c.fit().meanExcess() <= -3.0) {
			saveCompanion(c, preds);
		}
		return saved;
	}

	private void saveCompanion(Candidate c, String preds) {
		LearnedRule size = rules.findAll().stream().filter(r -> r.getKind() == LearnedRule.Kind.SIZE && r.getPredicates().equals(preds)).findFirst()
				.orElseGet(() -> new LearnedRule(LearnedRule.Kind.SIZE, c.predicates(), "", 0.6));
		size.describe(RuleText.describe(c.predicates(), c.fit().meanExcess(), c.fit().clusters(), c.fit().winRate()) + " — so positions in it are sized down.");
		size.recordEvidence(c.fit().trades(), c.fit().clusters(), c.fit().winRate(), c.fit().meanExcess(), c.holdoutClusters(), c.holdoutMean());
		if (size.getStatus() != LearnedRule.Status.ACTIVE) {
			size.startProbation(c.note());
		}
		rules.save(size);
	}

	/** Retire any active rule that, over all the data, no longer differs from the rest; refresh the stats of those that hold. */
	private void retireStale(List<Observation> observations, List<LearnedRule> justActivated) {
		Set<Long> keep = justActivated.stream().map(LearnedRule::getId).collect(Collectors.toSet());
		for (LearnedRule r : rules.findByStatusOrderByActivatedAtDesc(LearnedRule.Status.ACTIVE)) {
			if (keep.contains(r.getId())) continue;
			LessonMiner.Stats s = LessonMiner.recheck(observations, r.predicateSet());
			if (s.clusters() < RETIRE_MIN_CLUSTERS) continue; // not enough data either way — leave it
			boolean stillTrue = switch (r.getKind()) {
				case BOOST -> s.delta() >= RETIRE_DELTA_PTS && s.meanExcess() > 0;
				default -> s.delta() <= -RETIRE_DELTA_PTS && s.meanExcess() < 0;
			};
			if (stillTrue) {
				r.recordEvidence(s.trades(), s.clusters(), s.winRate(), s.meanExcess(), r.getHoldoutClusters(),
						r.getHoldoutMeanExcess() == null ? null : r.getHoldoutMeanExcess().doubleValue());
				rules.save(r);
			}
			else {
				r.retire(String.format(Locale.ROOT, "No longer holds: over all %d bets the situation averages %+.1f%% vs %+.1f%% for the rest.",
						s.clusters(), s.meanExcess(), s.complementMean()));
				rules.save(r);
				log.info("Trade learner: retired rule {} ({})", r.getId(), r.getDescription());
			}
		}
	}

	// ---- the model explains (never decides) ----

	private Optional<String> explain(Result result, List<Observation> observations, List<LearnedRule> newlyActive) {
		StringBuilder facts = new StringBuilder();
		facts.append(String.format(Locale.ROOT, "Baseline: %d independent bets, win rate %.0f%%, average %+.1f%% vs the S&P 500.%n",
				result.totalClusters(), result.baselineWin() * 100, result.baselineMean()));
		facts.append("Worst situations (vs the rest):\n").append(cohortLines(result.worstCohorts(), "lost"));
		facts.append("Best situations:\n").append(cohortLines(result.bestCohorts(), "earned"));
		facts.append("Patterns that looked promising on older trades but did NOT replicate on newer ones (do NOT present these as established):\n");
		List<Candidate> notReplicated = result.candidates().stream().filter(c -> c.decision() == LessonMiner.Decision.REJECT).toList();
		if (notReplicated.isEmpty()) facts.append("(none)\n");
		for (Candidate c : notReplicated) {
			facts.append(String.format(Locale.ROOT, "- %s (%s)%n", RuleText.subject(c.predicates()), c.note()));
		}
		facts.append("Rules that just entered probation (each replicated on newer trades; they change nothing until fresh trades confirm them):\n");
		if (newlyActive.isEmpty()) facts.append("(none)\n");
		for (LearnedRule r : newlyActive) {
			facts.append(String.format(Locale.ROOT, "- rule %d [%s]: %s%n", r.getId(), r.getKind(), r.getDescription()));
		}
		String prompt = """
				You are Argus's Trade Learner, reviewing the paper-trading record to understand why trades win and lose.
				Below are MEASURED facts. Do not invent any number or event that is not listed. The trades are plain \\
				stock positions (long or short) — do not mention options, leverage, derivatives or any instrument that is \\
				not in the facts. Only patterns listed under "entered probation" have replicated so far; anything listed as not replicated \\
				must be described as unproven if you mention it at all. Everything you write is a hypothesis for a human \\
				to weigh — you cannot change any rule.

				%s
				Respond with ONLY a JSON object:
				{"narrative":"3-5 sentences: what is losing, what is working, and what to weigh differently — citing only the numbers above",
				 "explanations":[{"id":<rule id>,"why":"one sentence: the most plausible reason this pattern loses/wins"}]}
				""".formatted(facts);
		try {
			Optional<JsonNode> parsed = LenientJsonParser.parseObject(gateway.generateFor(ClaudeFeature.LEARNER, prompt), log);
			if (parsed.isEmpty()) return Optional.empty();
			for (JsonNode e : parsed.get().path("explanations")) {
				long id = e.path("id").asLong(-1);
				newlyActive.stream().filter(r -> r.getId() == id).findFirst().ifPresent(r -> {
					r.explain(e.path("why").asString(""));
					rules.save(r);
				});
			}
			String narrative = parsed.get().path("narrative").asString("").strip();
			return narrative.isEmpty() ? Optional.empty() : Optional.of(narrative);
		}
		catch (RuntimeException ex) {
			log.warn("Trade learner: explanation call failed: {}", ex.getMessage());
			return Optional.empty();
		}
	}

	private static String cohortLines(List<LessonMiner.Cohort> cohorts, String verb) {
		if (cohorts.isEmpty()) return "";
		StringBuilder sb = new StringBuilder();
		for (LessonMiner.Cohort c : cohorts) {
			sb.append(String.format(Locale.ROOT, "- %s: %s %.1f%% vs the S&P over %d bets (%.1f points %s than the rest).%n", RuleText.subject(c.predicates()),
					verb, Math.abs(c.stats().meanExcess()), c.stats().clusters(), Math.abs(c.stats().delta()), c.stats().delta() < 0 ? "worse" : "better"));
		}
		return sb.toString();
	}
}
