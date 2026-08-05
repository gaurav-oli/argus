package com.argus.intelligence;

import com.argus.model.ModelGateway;
import jakarta.annotation.PostConstruct;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Keeps Agent 8's macro/political keyword list ({@link MacroRelevanceTagger}) growing — a fixed list
 * can never be exhaustive. Every time the LLM classification pass ({@link SentimentAnalyzer}'s
 * {@code macro} field) catches a story the keyword list didn't, {@link NewsSentimentAgent} logs it as
 * a {@link MacroKeywordMiss}. On a schedule, this service reviews the accumulated misses: the model
 * proposes new keywords, but — mirroring {@link com.argus.recommendation.LogicReviewService}'s
 * "LLM proposes, evidence decides" shape — a deterministic gate decides what actually ships, not the
 * model's say-so: a proposed keyword must independently, verifiably match ≥
 * {@code minCorroboration} distinct missed stories (re-checked in code, not trusted from the model's
 * response) and not be a known-ambiguous bare word. Every run is logged to
 * {@code macro_keyword_review}, adopted or not — that log is the "learning feedback" record.
 *
 * <p>This loop only has to get <em>recall</em> right (which stories get looked at at all) — it
 * deliberately doesn't try to prove a keyword improves trade outcomes the way
 * {@code LogicReviewService} backtests weight factors. That's already handled independently: the
 * generic {@code AdaptiveTuningService}/{@code LogicReviewService} loop tunes how much <em>trust</em>
 * {@code agent-8-macro}'s resulting signal gets, based on its own realized hit rate, regardless of
 * which keyword caught the underlying article.
 */
@Service
public class MacroKeywordLearningService {

	private static final Logger log = LoggerFactory.getLogger(MacroKeywordLearningService.class);
	private static final JsonMapper JSON = JsonMapper.builder().build();
	private static final int HEADLINE_KEY_LENGTH = 60;

	/**
	 * Known-ambiguous bare words that legitimately appear in ordinary company/sector news ("price
	 * war", "cyberattack", "bank earnings") — rejected as standalone keyword proposals regardless of
	 * corroboration. Multi-word phrases containing these are unaffected (e.g. "trade war" is fine).
	 */
	private static final Set<String> AMBIGUOUS_STOPLIST = Set.of(
			"war", "attack", "nuclear", "strike", "crash", "gas", "oil", "bank", "trade", "deal", "tax");

	private final MacroKeywordMissRepository misses;
	private final MacroKeywordRepository keywords;
	private final MacroRelevanceTagger tagger;
	private final ModelGateway gateway;
	private final JdbcTemplate jdbc;
	private final MacroKeywordLearningProperties props;

	public MacroKeywordLearningService(MacroKeywordMissRepository misses, MacroKeywordRepository keywords,
			MacroRelevanceTagger tagger, ModelGateway gateway, JdbcTemplate jdbc,
			MacroKeywordLearningProperties props) {
		this.misses = misses;
		this.keywords = keywords;
		this.tagger = tagger;
		this.gateway = gateway;
		this.jdbc = jdbc;
		this.props = props;
	}

	/** Sync the live tagger with the DB-backed list at startup, so a previously learned keyword survives a restart. */
	@PostConstruct
	void initFromDatabase() {
		List<String> dbKeywords = keywords.findAll().stream().map(MacroKeyword::getKeyword).toList();
		if (!dbKeywords.isEmpty()) {
			tagger.reload(dbKeywords);
			log.info("Macro keyword list loaded from database: {} keywords", dbKeywords.size());
		}
	}

	public record Proposal(String keyword, String why, int corroboration) {
	}

	public record Result(boolean ran, int missesConsidered, List<Proposal> proposals, List<Proposal> adopted,
			String reason) {
	}

	@Scheduled(cron = "${argus.macro-keyword-learning.cron:0 0 4 * * SUN}")
	public void scheduledReview() {
		try {
			review();
		}
		catch (RuntimeException ex) {
			log.warn("Macro keyword learning review failed: {}", ex.getMessage());
		}
	}

	/** One review pass: propose (model) → verify coverage + safety (deterministic) → adopt → log. */
	@Transactional
	public Result review() {
		if (!props.enabled()) {
			return persist(new Result(false, 0, List.of(), List.of(), "Macro keyword learning disabled."));
		}

		List<MacroKeywordMiss> unreviewed = misses.findByReviewedFalseOrderByDetectedAtAsc();
		if (unreviewed.size() < props.minMisses()) {
			String reason = "Not enough misses yet (%d < %d) — keeping current keyword list."
					.formatted(unreviewed.size(), props.minMisses());
			return persist(new Result(true, unreviewed.size(), List.of(), List.of(), reason));
		}

		List<MissCluster> clusters = cluster(unreviewed);
		List<String> current = tagger.currentKeywords();
		List<Proposal> proposed = propose(clusters, current);

		List<Proposal> adopted = new ArrayList<>();
		for (Proposal p : proposed) {
			if (clearsGate(p, props.minCorroboration(), keywords::existsById)) {
				adopted.add(p);
			}
		}

		String reason;
		if (proposed.isEmpty()) {
			reason = "Model proposed no keywords — keeping current list.";
		}
		else if (adopted.isEmpty()) {
			reason = "Proposed %d keyword(s), none cleared the corroboration/stoplist/duplicate gate."
					.formatted(proposed.size());
		}
		else if (!props.autoApply()) {
			reason = "%d keyword(s) cleared the gate but auto-apply is off — logged only."
					.formatted(adopted.size());
		}
		else {
			apply(adopted);
			reason = "Adopted %d of %d proposed keyword(s), each corroborated by %d+ distinct missed stories."
					.formatted(adopted.size(), proposed.size(), props.minCorroboration());
		}

		unreviewed.forEach(MacroKeywordMiss::markReviewed);
		misses.saveAll(unreviewed);

		Result result = new Result(true, unreviewed.size(), proposed, adopted, reason);
		log.info("Macro keyword learning: {}", reason);
		return persist(result);
	}

	// ---- model proposal ----

	private List<Proposal> propose(List<MissCluster> clusters, List<String> currentKeywords) {
		StringBuilder missesText = new StringBuilder();
		for (int i = 0; i < clusters.size(); i++) {
			MissCluster c = clusters.get(i);
			missesText.append(i + 1).append(". HEADLINE: ").append(c.headline())
					.append("\n   SUMMARY: ").append(c.summary() == null ? "" : c.summary()).append('\n');
		}
		String prompt = """
				You are reviewing Agent 8's macro/political news keyword detector. Below are real news \
				headlines a separate model judged to be macro/political/market-moving news, but the CURRENT \
				keyword list did not catch — so they were nearly missed entirely.

				CURRENT KEYWORDS: %s

				MISSED HEADLINES:
				%s
				Propose NEW keywords or short phrases (not already in the current list) that would have \
				caught these missed stories. Avoid bare single ambiguous words that appear in ordinary \
				company news too (e.g. "war", "bank", "trade", "deal", "tax") — prefer specific multi-word \
				phrases or proper nouns (country/leader/institution/event names). Respond with ONLY a JSON \
				array, no prose: [{"keyword":"...","why":"short reason"}]
				""".formatted(String.join(", ", currentKeywords), missesText);

		List<RawProposal> raw;
		try {
			raw = parse(gateway.generate(prompt));
		}
		catch (RuntimeException ex) {
			log.warn("Macro keyword learning model call failed ({}) — proposing no change", ex.getMessage());
			return List.of();
		}

		// Verify coverage independently in code — the model's say-so is never trusted for the count.
		List<Proposal> out = new ArrayList<>();
		for (RawProposal r : raw) {
			int corroboration = corroboration(r.keyword(), clusters);
			out.add(new Proposal(r.keyword(), r.why(), corroboration));
		}
		return out;
	}

	/** Package-visible for tests. */
	record RawProposal(String keyword, String why) {
	}

	/** Package-visible for tests. */
	static List<RawProposal> parse(String raw) {
		if (raw == null) {
			return List.of();
		}
		String s = raw.replace("```json", "").replace("```", "").strip();
		int lb = s.indexOf('['), rb = s.lastIndexOf(']');
		if (lb < 0 || rb <= lb) {
			return List.of();
		}
		List<RawProposal> out = new ArrayList<>();
		try {
			for (JsonNode n : JSON.readTree(s.substring(lb, rb + 1))) {
				String keyword = n.path("keyword").asString("").trim().toLowerCase(Locale.ROOT);
				if (keyword.isEmpty()) {
					continue;
				}
				out.add(new RawProposal(keyword, n.path("why").asString("").trim()));
			}
		}
		catch (RuntimeException ex) {
			log.warn("Macro keyword learning JSON parse failed: {}", ex.getMessage());
			return List.of();
		}
		return out;
	}

	/**
	 * The actual safety mechanism — not the model's say-so. A proposal ships only if it's corroborated
	 * by enough independently-verified distinct misses, isn't a known-ambiguous bare word, and isn't
	 * already in the list. Package-visible (pure, no I/O) for tests.
	 */
	static boolean clearsGate(Proposal p, int minCorroboration, Predicate<String> alreadyExists) {
		if (p.corroboration() < minCorroboration) {
			return false; // the model's suggestion doesn't hold up against the real misses
		}
		if (AMBIGUOUS_STOPLIST.contains(p.keyword())) {
			return false; // known false-positive risk as a bare word
		}
		return !alreadyExists.test(p.keyword()); // not already covered
	}

	/** How many distinct missed stories actually contain {@code keyword} — the real, verified gate.
	 * Package-visible for tests. */
	static int corroboration(String keyword, List<MissCluster> clusters) {
		Pattern p = Pattern.compile("\\b" + Pattern.quote(keyword) + "\\b", Pattern.CASE_INSENSITIVE);
		int count = 0;
		for (MissCluster c : clusters) {
			String haystack = safe(c.headline()) + " " + safe(c.summary());
			if (p.matcher(haystack).find()) {
				count++;
			}
		}
		return count;
	}

	// ---- adopt ----

	private void apply(List<Proposal> adopted) {
		for (Proposal p : adopted) {
			keywords.save(new MacroKeyword(p.keyword(), "learned", p.why(), p.corroboration()));
		}
		List<String> live = new ArrayList<>(tagger.currentKeywords());
		adopted.forEach(p -> live.add(p.keyword()));
		tagger.reload(live);
	}

	// ---- clustering (same normalize-headline shape as AgentSignalGatherer.clusterByHeadline) ----

	/** Package-visible for tests. */
	record MissCluster(String headline, String summary) {
	}

	/** Package-visible for tests. */
	static List<MissCluster> cluster(List<MacroKeywordMiss> misses) {
		Map<String, MissCluster> byKey = new LinkedHashMap<>();
		for (MacroKeywordMiss m : misses) {
			byKey.putIfAbsent(headlineKey(m.getHeadline()), new MissCluster(m.getHeadline(), m.getSummary()));
		}
		return List.copyOf(byKey.values());
	}

	private static String headlineKey(String headline) {
		String norm = safe(headline).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
		return norm.length() <= HEADLINE_KEY_LENGTH ? norm : norm.substring(0, HEADLINE_KEY_LENGTH);
	}

	private static String safe(String s) {
		return s == null ? "" : s;
	}

	// ---- persistence ----

	private Result persist(Result r) {
		List<Map<String, Object>> proposalsJson = toJson(r.proposals());
		List<Map<String, Object>> adoptedJson = toJson(r.adopted());
		jdbc.update("insert into macro_keyword_review (ran_at, model, misses_considered, proposals,"
				+ " adopted, reason) values (?, ?, ?, cast(? as jsonb), cast(? as jsonb), ?)",
				Timestamp.from(Instant.now()), r.ran() ? "gemma" : "n/a", r.missesConsidered(),
				JSON.writeValueAsString(proposalsJson), JSON.writeValueAsString(adoptedJson), r.reason());
		return r;
	}

	private static List<Map<String, Object>> toJson(List<Proposal> proposals) {
		List<Map<String, Object>> out = new ArrayList<>();
		for (Proposal p : proposals) {
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("keyword", p.keyword());
			m.put("why", p.why());
			m.put("corroboration", p.corroboration());
			out.add(m);
		}
		return out;
	}
}
