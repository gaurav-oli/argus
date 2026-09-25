package com.argus.learning;

import com.argus.learning.LessonMiner.Candidate;
import com.argus.learning.LessonMiner.Observation;
import com.argus.learning.LessonMiner.Result;
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
		return out;
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
			rule.activate(c.note());
		}
		else if (rule.getStatus() != LearnedRule.Status.ACTIVE) {
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
		size.activate(c.note());
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
		facts.append("Rules just activated (each replicated on newer trades):\n");
		if (newlyActive.isEmpty()) facts.append("(none)\n");
		for (LearnedRule r : newlyActive) {
			facts.append(String.format(Locale.ROOT, "- rule %d [%s]: %s%n", r.getId(), r.getKind(), r.getDescription()));
		}
		String prompt = """
				You are Argus's Trade Learner, reviewing the paper-trading record to understand why trades win and lose.
				Below are MEASURED facts. Do not invent any number or event that is not listed. The trades are plain \\
				stock positions (long or short) — do not mention options, leverage, derivatives or any instrument that is \\
				not in the facts. Only patterns listed under "activated" are established; anything listed as not replicated \\
				must be described as unproven if you mention it at all. Everything you write is a hypothesis for a human \\
				to weigh — you cannot change any rule.

				%s
				Respond with ONLY a JSON object:
				{"narrative":"3-5 sentences: what is losing, what is working, and what to weigh differently — citing only the numbers above",
				 "explanations":[{"id":<rule id>,"why":"one sentence: the most plausible reason this pattern loses/wins"}]}
				""".formatted(facts);
		try {
			Optional<JsonNode> parsed = LenientJsonParser.parseObject(gateway.generate(prompt, ModelTier.BIG), log);
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
