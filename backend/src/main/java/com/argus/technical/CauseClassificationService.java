package com.argus.technical;

import com.argus.intelligence.MacroRelevanceTagger;
import com.argus.intelligence.NewsArticle;
import com.argus.intelligence.NewsArticleRepository;
import com.argus.model.ModelGateway;
import com.argus.model.ModelTier;
import java.time.Duration;
import java.time.Instant;
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
 * Agent 11 — classifies <b>why</b> a ticker is in a meaningful drawdown, so a macro-driven,
 * temporary dip can be told apart from a real company-specific problem (the gap {@code
 * AgentSignalGatherer}'s existing sources can't close: News/Social/Macro just follow sentiment
 * down, they never ask what caused it). Only called when {@code AgentSignalGatherer} has already
 * confirmed a real drawdown crossed the configured trigger — this is a real LLM call, not a
 * deterministic computation, so it's deliberately bounded to real dips rather than run per ticker
 * per day.
 *
 * <p>Uses the free local-tier model ({@link ModelTier#BIG}, not {@code escalate()}) since this can
 * run across many held tickers — matches every other frequent/cheap agent call in the app, not the
 * rare-and-valuable paid-Haiku idiom. The LLM's job is classification only; per {@link
 * com.argus.recommendation.ProbabilityScoringEngine}'s "no number comes from an LLM" invariant, the
 * caller (not this service) turns the returned confidence into an actual signal weight.
 */
@Service
public class CauseClassificationService {

	private static final Logger log = LoggerFactory.getLogger(CauseClassificationService.class);
	private static final JsonMapper JSON = JsonMapper.builder().build();
	private static final Duration NEWS_WINDOW = Duration.ofDays(7);
	private static final int MAX_HEADLINES_PER_CATEGORY = 8;

	private final NewsArticleRepository news;
	private final ModelGateway gateway;

	public CauseClassificationService(NewsArticleRepository news, ModelGateway gateway) {
		this.news = news;
		this.gateway = gateway;
	}

	public enum Cause { COMPANY_SPECIFIC, MACRO_EXTERNAL, UNCLEAR }

	/** @param confidence 0-1, the LLM's own stated confidence in this classification — not itself a
	 *                    signal weight (see class javadoc) */
	public record Classification(Cause cause, boolean temporary, double confidence, String reasoning) {
	}

	/** Classify why {@code ticker} is down {@code drawdownPct}% from its recent high. Empty when
	 * there's no news to reason from (never guess), the model call fails, or the response can't be
	 * parsed into a real classification. */
	public Optional<Classification> classify(String ticker, double drawdownPct) {
		Instant since = Instant.now().minus(NEWS_WINDOW);
		List<NewsArticle> companyNews = news.findAnalyzedForTicker(ticker, since);
		List<NewsArticle> macroNews = news.findAnalyzedForTicker(MacroRelevanceTagger.MACRO_TAG, since);
		if (companyNews.isEmpty() && macroNews.isEmpty()) {
			return Optional.empty(); // nothing to reason from
		}
		String prompt = buildPrompt(ticker, drawdownPct, companyNews, macroNews);
		try {
			return parse(gateway.generate(prompt, ModelTier.BIG));
		}
		catch (RuntimeException ex) {
			log.warn("Cause classification for {} failed: {}", ticker, ex.getMessage());
			return Optional.empty();
		}
	}

	private static String buildPrompt(String ticker, double drawdownPct, List<NewsArticle> companyNews,
			List<NewsArticle> macroNews) {
		return """
				%s is down %.1f%% from its recent high. Classify why, using only the headlines below —
				do not use outside knowledge of this company.

				Company-specific headlines:
				%s

				Broad macro/political headlines (Fed policy, tariffs, geopolitics — not specific to %s):
				%s

				Respond with ONLY a JSON object, no prose: \
				{"cause":"COMPANY_SPECIFIC"|"MACRO_EXTERNAL"|"UNCLEAR","temporary":true|false,\
				"confidence":0.0-1.0,"reasoning":"one sentence"}

				COMPANY_SPECIFIC = the headlines point to a real problem at this company (earnings miss, \
				scandal, product failure, guidance cut). MACRO_EXTERNAL = the headlines point to broad \
				market/political events unrelated to this company's own fundamentals. UNCLEAR = the \
				headlines don't clearly support either read, or there isn't enough information — prefer \
				UNCLEAR over guessing. "temporary" should only be true when the cause looks like it will \
				pass rather than permanently change the company's prospects.
				""".formatted(ticker, drawdownPct, headlineList(companyNews), ticker, headlineList(macroNews));
	}

	private static String headlineList(List<NewsArticle> articles) {
		if (articles.isEmpty()) {
			return "(none)";
		}
		return articles.stream().limit(MAX_HEADLINES_PER_CATEGORY).map(a -> "- " + a.getHeadline())
				.collect(Collectors.joining("\n"));
	}

	/** Defensive JSON-object parse — same strip-fences/extract-span/safe-empty-on-failure shape as
	 * {@code LogicReviewService.parse}/{@code ResearchAgentService.parseSteps}, adapted for a single
	 * object instead of an array. */
	private static Optional<Classification> parse(String raw) {
		if (raw == null) {
			return Optional.empty();
		}
		String s = raw.replace("```json", "").replace("```", "").strip();
		int lb = s.indexOf('{');
		int rb = s.lastIndexOf('}');
		if (lb < 0 || rb <= lb) {
			return Optional.empty();
		}
		try {
			JsonNode n = JSON.readTree(s.substring(lb, rb + 1));
			Cause cause = parseCause(n.path("cause").asString("").trim());
			if (cause == null) {
				return Optional.empty();
			}
			boolean temporary = n.path("temporary").asBoolean(false);
			double confidence = n.path("confidence").asDouble(-1);
			if (Double.isNaN(confidence) || confidence < 0 || confidence > 1) {
				return Optional.empty();
			}
			String reasoning = n.path("reasoning").asString("").trim();
			return Optional.of(new Classification(cause, temporary, confidence, reasoning));
		}
		catch (RuntimeException ex) {
			log.warn("Cause classification JSON parse failed: {}", ex.getMessage());
			return Optional.empty();
		}
	}

	private static Cause parseCause(String raw) {
		try {
			return Cause.valueOf(raw.toUpperCase(Locale.ROOT));
		}
		catch (IllegalArgumentException ex) {
			return null; // never guess at an unrecognized value
		}
	}
}
