package com.argus.deepanalysis;

import com.argus.deepanalysis.DeepVerdictGuard.Specialist;
import com.argus.deepanalysis.DeepVerdictGuard.Stance;
import com.argus.learning.LessonEffect;
import com.argus.learning.Lessons;
import com.argus.model.LenientJsonParser;
import com.argus.model.ModelGateway;
import com.argus.model.ModelTier;
import com.argus.notification.Notification;
import com.argus.notification.NotificationService;
import com.argus.notification.UrgencyTier;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Agent 11 — the deep analyst. Given a ticker it takes as long as it needs (minutes to hours across a
 * whole universe) to think the stock through properly:
 *
 * <ol>
 *   <li><b>Gather</b> — {@link EvidenceCollector} engages every other agent and data source (news, insiders,
 *       crowd, earnings, macro and sector, Agent 10's chart study, Agent 12's fundamentals, the fast agents' signals).</li>
 *   <li><b>Four specialists</b> on the local model — chart technician, fundamental analyst, news/catalyst analyst,
 *       macro/sector strategist — each reads only its slice of the evidence and reports a stance and its reasoning.</li>
 *   <li><b>Skeptic</b> — attacks the emerging consensus.</li>
 *   <li><b>Portfolio manager</b> — a single verdict call (paid Claude Haiku when the budget allows, else local):
 *       <em>worth buying / wait / not worth buying</em>, and for a buy, how long to hold (7 / 30 / 90 days).</li>
 *   <li><b>{@link DeepVerdictGuard}</b> — deterministic guardrails that may only downgrade or cap the verdict when the
 *       data disagrees. The LLM proposes; the data disposes.</li>
 * </ol>
 * Every number in the evidence is computed by code; the models reason over it and never produce a figure.
 * The finished run stores the verdict, each analyst's reasoning, the guard's notes and the exact evidence
 * pack, so any verdict can be audited.
 */
@Service
public class DeepAnalystService {

	private static final Logger log = LoggerFactory.getLogger(DeepAnalystService.class);
	private static final JsonMapper JSON = JsonMapper.builder().build();
	private static final int MAX_TEXT = 4000;
	private static final double DEFAULT_SKEPTIC_SEVERITY = 0.3;

	private final EvidenceCollector collector;
	private final ModelGateway gateway;
	private final DeepAnalysisRepository repository;
	private final DeepAnalysisProperties props;
	private final NotificationService notifications;
	private final Lessons lessons;
	private final DeepScorecardService scorecard;

	public DeepAnalystService(EvidenceCollector collector, ModelGateway gateway, DeepAnalysisRepository repository,
			DeepAnalysisProperties props, NotificationService notifications, Lessons lessons, DeepScorecardService scorecard) {
		this.collector = collector;
		this.gateway = gateway;
		this.repository = repository;
		this.props = props;
		this.notifications = notifications;
		this.lessons = lessons;
		this.scorecard = scorecard;
	}

	/** One specialist's parsed report; {@code produced} is false when the analyst was skipped or failed. */
	record Report(String key, boolean produced, Stance stance, double strength, String summary, List<String> keyPoints,
			List<String> risks) {

		static Report absent(String key, String why) {
			return new Report(key, false, Stance.NEUTRAL, 0, why, List.of(), List.of());
		}

		String digest() {
			if (!produced) {
				return "- " + key + ": no view (" + summary + ")\n";
			}
			return String.format(Locale.ROOT, "- %s: %s (strength %.2f) — %s Points: %s Risks: %s%n", key, stance, strength, summary,
					String.join("; ", keyPoints), String.join("; ", risks));
		}

		String display() {
			if (!produced) {
				return summary;
			}
			StringBuilder sb = new StringBuilder(String.format(Locale.ROOT, "%s (strength %.0f%%). %s", stance, strength * 100, summary));
			keyPoints.forEach(p -> sb.append("\n• ").append(p));
			if (!risks.isEmpty()) {
				sb.append("\nRisks: ").append(String.join("; ", risks));
			}
			return sb.toString();
		}
	}

	/** Run analysis {@code id} to completion (or failure). Synchronous — the runner backgrounds it. */
	public void analyze(Long id) {
		DeepAnalysis run = repository.findById(id).orElse(null);
		if (run == null) {
			return;
		}
		Instant started = Instant.now();
		try {
			run.markRunning();
			repository.save(run);
			List<String> timings = new ArrayList<>();
			String ticker = run.getTicker();

			stage(run, "Gathering evidence from the other agents");
			Evidence ev = collector.collect(ticker);
			run.recordEvidence(ev.fullText());
			repository.save(run);

			Report technical = ev.chart() == null ? Report.absent("technical", "not enough price history to read the chart")
					: specialist(run, "technical", "Chart technician reading the candles", DeepPrompts.technical(ticker, ev.technicalSection()), timings);
			Report fundamental = ev.fundamentals() == null || !ev.fundamentals().applicable()
					? Report.absent("fundamental", "no company fundamentals (ETF/fund or unrecognised symbol)")
					: specialist(run, "fundamental", "Fundamental analyst reading the financials", DeepPrompts.fundamental(ticker, ev.fundamentalSection()), timings);
			Report catalyst = specialist(run, "catalyst", "News & catalyst analyst", DeepPrompts.catalyst(ticker, ev.catalystSection()), timings);
			Report macro = specialist(run, "macro", "Macro & sector strategist", DeepPrompts.macro(ticker, ev.macroSection()), timings);

			List<Report> reports = List.of(technical, fundamental, catalyst, macro);
			long produced = reports.stream().filter(Report::produced).count();
			if (produced < 2) {
				throw new IllegalStateException("Only " + produced + " of the specialist analysts produced usable output — too little to reach a verdict.");
			}
			String digest = reports.stream().map(Report::digest).collect(Collectors.joining());
			// What has Argus learned from its own past trades in a situation like this one? The same lessons that
			// shape the quick recommender and the Investor inform the deep analyst — in its prompts, and (below)
			// as a deterministic guardrail on the verdict it reaches.
			java.util.Set<String> tokens = ev.featureTokens();
			LessonEffect lessonFx = lessons.evaluate(tokens);
			String lessonText = lessons.promptSection(tokens);

			double skepticSeverity = DEFAULT_SKEPTIC_SEVERITY;
			String skepticText;
			pause();
			stage(run, "Skeptic challenging the consensus");
			Optional<JsonNode> sk = callJson(DeepPrompts.skeptic(ticker, ev.overview(), digest, lessonText), timings, "skeptic");
			if (sk.isPresent()) {
				skepticSeverity = Math.max(0, Math.min(1, sk.get().path("severity").asDouble(DEFAULT_SKEPTIC_SEVERITY)));
				skepticText = clip(sk.get().path("summary").asString("")) + list(sk.get().path("counterpoints"), "\n• ");
			}
			else {
				skepticText = "The skeptic produced no usable output; a moderate default severity was applied.";
			}

			pause();
			stage(run, "Portfolio manager weighing the evidence");
			VerdictOutcome verdictOutcome = verdictCall(DeepPrompts.verdict(ticker, ev.overview(), ev.quickAgents(), digest,
					"Severity " + String.format(Locale.ROOT, "%.2f", skepticSeverity) + ". " + skepticText, lessonText), timings)
					.orElseThrow(() -> new IllegalStateException("The portfolio-manager step returned no usable verdict."));
			JsonNode draftNode = verdictOutcome.json();
			run.recordVerdictModel(verdictOutcome.model());
			DeepVerdict proposed = parseVerdict(draftNode.path("verdict").asString(""));
			if (proposed == null) {
				throw new IllegalStateException("The portfolio-manager verdict was not one of WORTH_BUYING / WAIT / NOT_WORTH_BUYING.");
			}
			Integer proposedHold = draftNode.path("holdDays").isNumber() ? draftNode.path("holdDays").asInt() : null;
			int proposedConviction = draftNode.path("conviction").asInt(50);

			stage(run, "Applying deterministic guardrails");
			List<Specialist> specialists = List.of(
					new Specialist("technical", technical.stance(), technical.strength(), technical.produced() ? 1.0 : 0),
					new Specialist("fundamental", fundamental.stance(), fundamental.strength(), fundamental.produced() ? 1.2 : 0),
					new Specialist("catalyst", catalyst.stance(), catalyst.strength(), catalyst.produced() ? 1.0 : 0),
					new Specialist("macro", macro.stance(), macro.strength(), macro.produced() ? 0.6 : 0));
			DeepVerdictGuard.Result guarded = DeepVerdictGuard.apply(new DeepVerdictGuard.Draft(proposed, proposedHold, proposedConviction),
					new DeepVerdictGuard.Input(specialists, skepticSeverity, ev.fundamentals() != null && ev.fundamentals().applicable(),
							ev.fundamentals() == null ? null : ev.fundamentals().score(), ev.etf(), ev.lastPrice(), ev.earningsInTradingDays(),
							ev.chart() != null, lessonFx, safeTrackRecord(proposed)));

			DeepVerdict previous = repository.findFirstByTickerAndStatusOrderByFinishedAtDesc(ticker, DeepAnalysis.Status.DONE)
					.map(DeepAnalysis::getVerdict).orElse(null);
			run.recordScores(ev.chart() == null ? null : ev.chart().score(), ev.fundamentals() == null || !ev.fundamentals().applicable() ? null
					: ev.fundamentals().score(), guarded.consensus());
			run.recordSpecialists(technical.display(), fundamental.display(), catalyst.display(), macro.display(), skepticText);
			run.recordStages(stagesJson(timings, started), "local specialists · verdict via " + (props.useHaikuForVerdict() ? "Claude Haiku (budget-governed)" : "local model"));
			InvalidationLevel.Resolved level = InvalidationLevel.resolve(guarded.verdict(),
					draftNode.path("invalidationPrice").isNumber() ? draftNode.path("invalidationPrice").asDouble() : null, ev.lastPrice(),
					ev.chart() == null ? null : ev.chart().support(), ev.chart() == null ? null : ev.chart().resistance(),
					ev.chart() == null ? null : ev.chart().atrPct());
			run.recordEntry(ev.lastPrice(), level.price());
			run.complete(guarded.verdict(), guarded.holdDays(), guarded.conviction(), clip(draftNode.path("headline").asString("")),
					clip(draftNode.path("thesis").asString("")), clip(draftNode.path("bullCase").asString("")), clip(draftNode.path("bearCase").asString("")),
					lines(draftNode.path("risks")), lines(draftNode.path("catalysts")), clip(draftNode.path("invalidation").asString("")),
					String.join("\n", guarded.notes()) + (level.price() != null && !level.modelLevelUsed()
							? (guarded.notes().isEmpty() ? "" : "\n") + "The model's invalidation level was missing or unusable; used the chart-based level "
							+ level.price() + " instead." : ""), props.signalTtl());
			repository.save(run);
			log.info("Agent 11: {} → {} (conviction {}, hold {}) in {}s", ticker, guarded.verdict(), guarded.conviction(), guarded.holdDays(),
					Duration.between(started, Instant.now()).toSeconds());
			announce(run, previous, ev);
		}
		catch (RuntimeException ex) {
			log.warn("Agent 11: analysis of {} failed: {}", run.getTicker(), ex.getMessage());
			run.fail(ex.getMessage() == null ? "Unexpected error" : ex.getMessage());
			try {
				repository.save(run);
			}
			catch (RuntimeException saveEx) {
				log.warn("Agent 11: could not persist failure for {}: {}", run.getTicker(), saveEx.getMessage());
			}
		}
	}

	/** The scorecard is best-effort feedback: a failure here must never fail an analysis. */
	private TrackRecord safeTrackRecord(DeepVerdict verdict) {
		if (verdict == DeepVerdict.WAIT) return null;
		try {
			return scorecard.trackRecord(verdict).orElse(null);
		}
		catch (RuntimeException ex) {
			log.debug("Agent 11: track record unavailable: {}", ex.getMessage());
			return null;
		}
	}

	// ---- stages ----

	private Report specialist(DeepAnalysis run, String key, String stageLabel, String prompt, List<String> timings) {
		pause();
		stage(run, stageLabel);
		Optional<JsonNode> out = callJson(prompt, timings, key);
		if (out.isEmpty()) {
			return Report.absent(key, "the analyst's output could not be parsed");
		}
		JsonNode n = out.get();
		Stance stance = parseStance(n.path("stance").asString(""));
		double strength = Math.max(0, Math.min(1, n.path("strength").asDouble(0)));
		return new Report(key, true, stance, strength, clip(n.path("summary").asString("")), strings(n.path("keyPoints")), strings(n.path("risks")));
	}

	/** Local model, JSON-parsed, one repair retry. */
	private Optional<JsonNode> callJson(String prompt, List<String> timings, String label) {
		long t0 = System.nanoTime();
		try {
			Optional<JsonNode> parsed = LenientJsonParser.parseObject(gateway.generate(prompt, ModelTier.BIG), log);
			if (parsed.isEmpty()) {
				parsed = LenientJsonParser.parseObject(gateway.generate(prompt + "\nYour previous reply was not valid JSON. Reply with ONLY the JSON object.",
						ModelTier.BIG), log);
			}
			return parsed;
		}
		catch (RuntimeException ex) {
			log.warn("Agent 11: {} call failed: {}", label, ex.getMessage());
			return Optional.empty();
		}
		finally {
			timings.add(label + ":" + (System.nanoTime() - t0) / 1_000_000_000L + "s");
		}
	}

	/** Which model actually produced the parsed verdict JSON — "HAIKU" or "LOCAL" — for tracking outcomes by model. */
	private record VerdictOutcome(JsonNode json, String model) {
	}

	/**
	 * The verdict call: paid Haiku when allowed (budget-governed inside the gateway), falling back to the local
	 * model. The repair retry for unparseable JSON always goes to the local model regardless of who answered
	 * first — so {@code model} reflects whichever call's output actually got used, not just which was attempted.
	 */
	private Optional<VerdictOutcome> verdictCall(String prompt, List<String> timings) {
		long t0 = System.nanoTime();
		try {
			String raw;
			String model;
			if (props.useHaikuForVerdict()) {
				try {
					raw = gateway.escalate(prompt);
					model = "HAIKU";
				}
				catch (RuntimeException ex) {
					log.info("Agent 11: Haiku verdict unavailable ({}) — using the local model", ex.getMessage());
					raw = gateway.generate(prompt, ModelTier.BIG);
					model = "LOCAL";
				}
			}
			else {
				raw = gateway.generate(prompt, ModelTier.BIG);
				model = "LOCAL";
			}
			Optional<JsonNode> parsed = LenientJsonParser.parseObject(raw, log);
			if (parsed.isEmpty()) {
				parsed = LenientJsonParser.parseObject(gateway.generate(prompt + "\nYour previous reply was not valid JSON. Reply with ONLY the JSON object.",
						ModelTier.BIG), log);
				model = "LOCAL";
			}
			String finalModel = model;
			return parsed.map(json -> new VerdictOutcome(json, finalModel));
		}
		catch (RuntimeException ex) {
			log.warn("Agent 11: verdict call failed: {}", ex.getMessage());
			return Optional.empty();
		}
		finally {
			timings.add("verdict:" + (System.nanoTime() - t0) / 1_000_000_000L + "s");
		}
	}

	private void stage(DeepAnalysis run, String label) {
		run.setStage(label);
		repository.save(run);
	}

	/** Leave room between LLM stages so interactive callers (Ask AI, personas) are not starved of the model. */
	private void pause() {
		long ms = props.stageGap().toMillis();
		if (ms <= 0) return;
		try {
			Thread.sleep(ms);
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
		}
	}

	/** Only announce a verdict that changes to WORTH_BUYING with real conviction — not every nightly re-run. */
	private void announce(DeepAnalysis run, DeepVerdict previous, Evidence ev) {
		if (run.getVerdict() != DeepVerdict.WORTH_BUYING || run.getConviction() == null || run.getConviction() < 70 || previous == DeepVerdict.WORTH_BUYING) {
			return;
		}
		boolean dip = ev.chart() != null && ev.chart().drawdown60dPct() != null && ev.chart().drawdown60dPct() <= props.dipAlertDrawdownPct();
		try {
			notifications.notify(Notification.forTicker(UrgencyTier.IMPORTANT, run.getTicker(), "BULLISH", run.getConviction() / 100.0, 1.0,
					(dip ? "📉 Possible buying opportunity: " : "🔎 Deep analysis: worth buying — ") + run.getTicker(),
					(run.getHeadline() == null ? "" : run.getHeadline() + " ") + "Hold about " + run.getHoldDays() + " days. Conviction " + run.getConviction() + "/100.",
					"/intelligence"));
		}
		catch (RuntimeException ex) {
			log.warn("Agent 11: announcement for {} failed: {}", run.getTicker(), ex.getMessage());
		}
	}

	// ---- parsing helpers ----

	static DeepVerdict parseVerdict(String raw) {
		try {
			return DeepVerdict.valueOf(raw.trim().toUpperCase(Locale.ROOT).replace(' ', '_').replace('-', '_'));
		}
		catch (RuntimeException ex) {
			return null;
		}
	}

	static Stance parseStance(String raw) {
		try {
			return Stance.valueOf(raw.trim().toUpperCase(Locale.ROOT));
		}
		catch (RuntimeException ex) {
			return Stance.NEUTRAL;
		}
	}

	private static List<String> strings(JsonNode arr) {
		List<String> out = new ArrayList<>();
		if (arr != null && arr.isArray()) {
			for (JsonNode n : arr) {
				String s = n.asString("").trim();
				if (!s.isEmpty()) out.add(clip(s));
			}
		}
		return out;
	}

	private static String lines(JsonNode arr) {
		return String.join("\n", strings(arr));
	}

	private static String list(JsonNode arr, String bullet) {
		List<String> s = strings(arr);
		return s.isEmpty() ? "" : bullet + String.join(bullet, s);
	}

	private static String clip(String s) {
		if (s == null) return "";
		String t = s.strip();
		return t.length() <= MAX_TEXT ? t : t.substring(0, MAX_TEXT - 1) + "…";
	}

	private static String stagesJson(List<String> timings, Instant started) {
		return JSON.writeValueAsString(java.util.Map.of("timings", timings, "totalSeconds", Duration.between(started, Instant.now()).toSeconds()));
	}
}
