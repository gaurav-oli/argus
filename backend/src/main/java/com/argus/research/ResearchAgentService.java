package com.argus.research;

import com.argus.calendar.CalendarEvent;
import com.argus.calendar.CalendarEventRepository;
import com.argus.calendar.CalendarEventType;
import com.argus.common.BadRequestException;
import com.argus.common.LivePushService;
import com.argus.intelligence.MacroRelevanceTagger;
import com.argus.intelligence.NewsArticle;
import com.argus.intelligence.NewsArticleRepository;
import com.argus.internet.WebMention;
import com.argus.internet.WebMentionRepository;
import com.argus.marketdata.FinnhubRest;
import com.argus.model.ModelGateway;
import com.argus.model.ModelTier;
import com.argus.sec.SecFiling;
import com.argus.sec.SecFilingRepository;
import com.argus.social.SocialPost;
import com.argus.social.SocialPostRepository;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Agent 9 — on-demand research. Unlike every other agent, this one only runs when asked: given a
 * ticker, it plans its own steps ({@link #plan}), works through them gathering real data from the same
 * sources Agents 1/2/4/8 already collect ({@link #gather}), checks after each step whether the
 * remaining plan should change ({@link #replanCheck}), then synthesizes a report ({@link #synthesize}).
 *
 * <p>Cost is bounded regardless of how many replans happen: the plan and every replan check use the
 * local model ({@code ModelGateway.generate(..., BIG)}, free); only the single final synthesis call
 * uses {@code escalate()} (paid Haiku) — the same "rare, valuable, user-initiated" idiom
 * {@code ConversationService}'s "deeper analysis" already uses.
 *
 * <p>FINANCIALS (fast-follow) pulls key ratios from Finnhub's {@code /stock/metric} — confirmed
 * available on existing credentials, no new key needed. Full financial-<em>statement</em> data (line-item
 * P&amp;L/balance-sheet figures via SEC EDGAR XBRL company facts) remains deliberately deferred: EDGAR
 * keys off CIK, not ticker, and a ticker→CIK resolver is its own small subsystem, not a one-line
 * addition. Likewise 10-K/10-Q narrative parsing (MD&amp;A/risk factors) and peer/competitor
 * comparison are out of scope for this pass. The synthesis prompt is honest about what it wasn't
 * given rather than inventing numbers.
 */
@Service
public class ResearchAgentService {

	private static final Logger log = LoggerFactory.getLogger(ResearchAgentService.class);
	private static final JsonMapper JSON = JsonMapper.builder().build();
	private static final Pattern TICKER_PATTERN = Pattern.compile("^[A-Z]{1,6}(\\.[A-Z])?$");
	private static final List<String> DATA_SOURCES =
			List.of("NEWS", "MACRO", "SOCIAL", "INSIDER", "WEB", "EARNINGS", "FINANCIALS");
	private static final int EARNINGS_LOOKAHEAD_DAYS = 90;

	private final ResearchJobRepository jobs;
	private final NewsArticleRepository news;
	private final SocialPostRepository social;
	private final SecFilingRepository sec;
	private final WebMentionRepository web;
	private final CalendarEventRepository calendar;
	private final ModelGateway gateway;
	private final LivePushService livePush;
	private final ResearchJobProperties props;
	private final FinnhubRest finnhub;
	private final String finnhubApiKey;
	private final ExecutorService executor;

	public ResearchAgentService(ResearchJobRepository jobs, NewsArticleRepository news,
			SocialPostRepository social, SecFilingRepository sec, WebMentionRepository web,
			CalendarEventRepository calendar, ModelGateway gateway, LivePushService livePush,
			ResearchJobProperties props, FinnhubRest finnhub,
			@org.springframework.beans.factory.annotation.Value("${argus.finnhub.api-key:}") String finnhubApiKey) {
		this.jobs = jobs;
		this.news = news;
		this.social = social;
		this.sec = sec;
		this.web = web;
		this.calendar = calendar;
		this.gateway = gateway;
		this.livePush = livePush;
		this.props = props;
		this.finnhub = finnhub;
		this.finnhubApiKey = finnhubApiKey;
		AtomicInteger threadNum = new AtomicInteger();
		this.executor = Executors.newFixedThreadPool(Math.max(1, props.maxConcurrentJobs()), r -> {
			Thread t = new Thread(r, "research-agent-" + threadNum.incrementAndGet());
			t.setDaemon(true);
			return t;
		});
	}

	@PreDestroy
	void shutdown() {
		executor.shutdownNow();
		try {
			executor.awaitTermination(5, TimeUnit.SECONDS);
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
		}
	}

	/** Start a research pass for {@code rawTicker}; returns immediately, the pipeline runs in the
	 * background. Rejects an obviously-malformed ticker before ever touching the executor.
	 *
	 * <p>Deliberately NOT {@code @Transactional}: {@link org.springframework.data.jpa.repository.JpaRepository#save}
	 * already commits in its own transaction when called from a non-transactional method, so by the
	 * time {@code executor.submit} runs, the insert is guaranteed durable and visible to the background
	 * thread's own connection. Wrapping this method in {@code @Transactional} would submit the
	 * background task <em>before</em> the surrounding transaction commits — under load, the background
	 * thread can start and call {@code jobs.findById} before that commit lands, see nothing, and
	 * silently no-op forever (found the hard way: an integration test that starts and awaits a job was
	 * flaky specifically under a full-suite run, never in isolation — more DB/thread contention made
	 * the race easier to lose).
	 */
	public ResearchJob startJob(String rawTicker) {
		String ticker = normalizeTicker(rawTicker);
		ResearchJob job = jobs.save(new ResearchJob(ticker));
		executor.submit(() -> runPipeline(job.getId()));
		return job;
	}

	private static String normalizeTicker(String raw) {
		if (raw == null || raw.isBlank()) {
			throw new BadRequestException("Ticker must not be empty.");
		}
		String ticker = raw.trim().toUpperCase(Locale.ROOT);
		if (!TICKER_PATTERN.matcher(ticker).matches()) {
			throw new BadRequestException("'" + raw + "' doesn't look like a valid ticker symbol.");
		}
		return ticker;
	}

	// ---- pipeline ----

	/** The actual pipeline — synchronous. {@link #startJob} is what backgrounds it via the executor;
	 * package-visible so tests can drive it directly and deterministically rather than racing a
	 * background thread. */
	void runPipeline(Long jobId) {
		ResearchJob job = jobs.findById(jobId).orElse(null);
		if (job == null) {
			return;
		}
		try {
			List<Step> plan = plan(job.getTicker());
			job.applyPlan(writePlan(plan));
			job.applyStatus(ResearchJob.Status.RESEARCHING);
			jobs.save(job);
			pushUpdate(job, plan);

			Map<String, String> findings = new LinkedHashMap<>();
			List<Step> remaining = new ArrayList<>(plan);
			int i = 0;
			while (i < remaining.size()) {
				Step step = remaining.get(i).withStatus("RUNNING");
				remaining.set(i, step);
				job.applyPlan(writePlan(remaining));
				jobs.save(job);
				pushUpdate(job, remaining);

				String finding = gather(job.getTicker(), step.dataSource());
				findings.put(step.id(), finding);
				job.applyFindings(writeFindings(findings));

				remaining.set(i, step.withStatus("DONE"));
				job.applyPlan(writePlan(remaining));
				jobs.save(job);
				pushUpdate(job, remaining);

				List<Step> revised = replanCheck(job.getTicker(), remaining, i, finding);
				if (revised != null) {
					job.applyStatus(ResearchJob.Status.REVISING_PLAN);
					job.applyPlan(writePlan(revised));
					jobs.save(job);
					pushUpdate(job, revised);
					remaining = revised;
					job.applyStatus(ResearchJob.Status.RESEARCHING);
					jobs.save(job);
				}
				i++;
			}

			job.applyStatus(ResearchJob.Status.SYNTHESIZING);
			jobs.save(job);
			pushUpdate(job, remaining);

			String report = synthesize(job.getTicker(), remaining, findings);
			job.complete(report);
			jobs.save(job);
			pushUpdate(job, remaining);
			log.info("Agent 9: research on {} complete ({} steps)", job.getTicker(), remaining.size());
		}
		catch (RuntimeException ex) {
			log.warn("Agent 9: research on {} failed: {}", job.getTicker(), ex.getMessage());
			job.fail(ex.getMessage() == null ? "Unexpected error" : ex.getMessage());
			// Best-effort like pushUpdate below — if persistence itself is what's failing, the job's
			// in-memory state (and the live push) still reflect FAILED rather than throwing out of the
			// background thread and leaving the job silently stuck in its last successfully-saved state.
			try {
				jobs.save(job);
			}
			catch (RuntimeException saveEx) {
				log.warn("Agent 9: could not persist failure state for {}: {}", job.getTicker(), saveEx.getMessage());
			}
			pushUpdate(job, readPlan(job.getPlan()));
		}
	}

	private void pushUpdate(ResearchJob job, List<Step> plan) {
		try {
			livePush.publish("/topic/research/" + job.getId(), ResearchJobView.from(job, plan));
		}
		catch (RuntimeException ex) {
			log.debug("Agent 9: live push failed for job {}: {}", job.getId(), ex.getMessage());
		}
	}

	// ---- plan ----

	private List<Step> plan(String ticker) {
		String prompt = """
				You are Agent 9, an on-demand research assistant. A user asked you to research the stock %s.
				You have access to these data sources — use each at most once, only include ones relevant:
				- NEWS: recent news headlines and sentiment about %s specifically
				- MACRO: broad macro/political news (central bank policy, tariffs, elections, geopolitics) that could move the whole market
				- SOCIAL: crowd sentiment from StockTwits/Reddit
				- INSIDER: recent insider (Form 4) buy/sell filings
				- WEB: Hacker News discussion and Wikipedia attention
				- EARNINGS: next earnings date and recent EPS-surprise history
				- FINANCIALS: key valuation/profitability/leverage ratios (P/E, margins, ROE, debt/equity)

				Propose an ordered research plan, 3-7 steps, each using exactly one data source.
				Respond with ONLY a JSON array, no prose: \
				[{"label":"short step name","dataSource":"NEWS|MACRO|SOCIAL|INSIDER|WEB|EARNINGS|FINANCIALS","why":"one sentence"}]
				""".formatted(ticker, ticker);
		try {
			List<Step> parsed = parseSteps(gateway.generate(prompt, ModelTier.BIG));
			if (!parsed.isEmpty()) {
				return parsed;
			}
		}
		catch (RuntimeException ex) {
			log.warn("Agent 9: plan generation failed for {} ({}) — using the default plan", ticker, ex.getMessage());
		}
		return defaultPlan();
	}

	/** Every data source in a sensible order — the fallback when planning fails or returns nothing
	 * usable, so a model hiccup never leaves a job with an empty plan. */
	private static List<Step> defaultPlan() {
		List<Step> steps = new ArrayList<>();
		steps.add(new Step("s1", "Recent news & sentiment", "NEWS", "Company-specific coverage first.", "PENDING"));
		steps.add(new Step("s2", "Macro/political backdrop", "MACRO", "Broad market-moving context.", "PENDING"));
		steps.add(new Step("s3", "Crowd sentiment", "SOCIAL", "StockTwits/Reddit chatter.", "PENDING"));
		steps.add(new Step("s4", "Insider activity", "INSIDER", "Recent Form 4 buys/sells.", "PENDING"));
		steps.add(new Step("s5", "Web attention", "WEB", "Hacker News + Wikipedia interest.", "PENDING"));
		steps.add(new Step("s6", "Earnings picture", "EARNINGS", "Next date and recent EPS surprises.", "PENDING"));
		steps.add(new Step("s7", "Key financial ratios", "FINANCIALS", "Valuation, margins, leverage.", "PENDING"));
		return steps;
	}

	// ---- replan ----

	/** Returns a revised remaining-steps list (already includes the completed prefix), or {@code null}
	 * when the model has no change to suggest — bounded to one check per completed step, never an
	 * open-ended loop. */
	private List<Step> replanCheck(String ticker, List<Step> remaining, int justCompletedIndex, String finding) {
		if (justCompletedIndex + 1 >= remaining.size()) {
			return null; // nothing left to revise
		}
		List<Step> upcoming = remaining.subList(justCompletedIndex + 1, remaining.size());
		String prompt = """
				You are researching %s. You just completed the step "%s" with these findings:
				%s

				Remaining planned steps: %s

				If these findings suggest the remaining plan should change (add a step for a data source not \
				yet used, remove one that's now clearly unnecessary, or reorder), respond with ONLY a JSON \
				array of the revised remaining steps, same shape as before: \
				[{"label":"...","dataSource":"NEWS|MACRO|SOCIAL|INSIDER|WEB|EARNINGS|FINANCIALS","why":"..."}]
				If the plan is still fine as-is, respond with exactly: NO_CHANGE
				""".formatted(ticker, remaining.get(justCompletedIndex).label(), finding,
				upcoming.stream().map(Step::label).collect(Collectors.joining(", ")));
		try {
			String raw = gateway.generate(prompt, ModelTier.BIG);
			if (raw == null || raw.strip().toUpperCase(Locale.ROOT).contains("NO_CHANGE") || !raw.contains("[")) {
				return null;
			}
			List<Step> revisedUpcoming = parseSteps(raw);
			if (revisedUpcoming.isEmpty()) {
				return null;
			}
			List<Step> result = new ArrayList<>(remaining.subList(0, justCompletedIndex + 1));
			result.addAll(revisedUpcoming);
			return result;
		}
		catch (RuntimeException ex) {
			log.debug("Agent 9: replan check failed for {} ({}) — keeping the existing plan", ticker, ex.getMessage());
			return null;
		}
	}

	// ---- data gathering (deterministic, no LLM) ----

	private String gather(String ticker, String dataSource) {
		try {
			Instant since = Instant.now().minus(Duration.ofDays(props.dataWindowDays()));
			return switch (dataSource) {
				case "NEWS" -> summarizeNews(news.findAnalyzedForTicker(ticker, since));
				case "MACRO" -> summarizeNews(news.findAnalyzedForTicker(MacroRelevanceTagger.MACRO_TAG, since));
				case "SOCIAL" -> summarizeSocial(social.findByTickerAndPostedAtAfter(ticker, since));
				case "INSIDER" -> summarizeInsider(sec.findByTickerAndTransactionTypeInAndFiledAtAfter(
						ticker, List.of("BUY", "SELL"), LocalDate.now().minusDays(props.dataWindowDays())));
				case "WEB" -> summarizeWeb(web.findByTickerAndPostedAtAfter(ticker, since));
				case "EARNINGS" -> summarizeEarnings(ticker);
				case "FINANCIALS" -> summarizeFinancials(ticker);
				default -> "Unrecognized data source \"" + dataSource + "\" — skipped.";
			};
		}
		catch (RuntimeException ex) {
			log.warn("Agent 9: gathering {} for {} failed: {}", dataSource, ticker, ex.getMessage());
			return "Data gathering failed for this step (" + ex.getMessage() + ").";
		}
	}

	private static String summarizeNews(List<NewsArticle> articles) {
		if (articles.isEmpty()) {
			return "No recent news found in the window.";
		}
		double avgSentiment = articles.stream()
				.filter(a -> a.getSentimentScore() != null)
				.mapToDouble(a -> a.getSentimentScore().doubleValue())
				.average().orElse(0);
		String headlines = articles.stream().limit(8).map(NewsArticle::getHeadline)
				.collect(Collectors.joining("; "));
		return String.format(Locale.ROOT, "%d articles, average sentiment %.2f (-1..1). Headlines: %s",
				articles.size(), avgSentiment, headlines);
	}

	private static String summarizeSocial(List<SocialPost> posts) {
		if (posts.isEmpty()) {
			return "No recent social posts found in the window.";
		}
		long bullish = posts.stream().filter(p -> p.getSentimentLabel() != null
				&& "BULLISH".equals(p.getSentimentLabel().name())).count();
		long bearish = posts.stream().filter(p -> p.getSentimentLabel() != null
				&& "BEARISH".equals(p.getSentimentLabel().name())).count();
		return String.format(Locale.ROOT, "%d posts (%d bullish / %d bearish / %d neutral).",
				posts.size(), bullish, bearish, posts.size() - bullish - bearish);
	}

	private static String summarizeInsider(List<SecFiling> filings) {
		if (filings.isEmpty()) {
			return "No insider Form 4 activity found in the window.";
		}
		long buys = filings.stream().filter(f -> "BUY".equals(f.getTransactionType())).count();
		long sells = filings.size() - buys;
		String detail = filings.stream().limit(5)
				.map(f -> f.getInsiderName() + " (" + f.getInsiderTitle() + "): " + f.getTransactionType())
				.collect(Collectors.joining("; "));
		return String.format(Locale.ROOT, "%d filing(s): %d buy(s), %d sell(s). %s", filings.size(), buys, sells, detail);
	}

	private static String summarizeWeb(List<WebMention> mentions) {
		if (mentions.isEmpty()) {
			return "No Hacker News or Wikipedia attention found in the window.";
		}
		long hn = mentions.stream().filter(m -> "hackernews".equals(m.getSource())).count();
		long wiki = mentions.size() - hn;
		return String.format(Locale.ROOT, "%d web mention(s): %d Hacker News, %d Wikipedia pageview spike(s).",
				mentions.size(), hn, wiki);
	}

	private String summarizeEarnings(String ticker) {
		LocalDate today = LocalDate.now();
		List<CalendarEvent> upcoming = calendar.findByTickerAndTypeAndEventDateBetweenOrderByEventDateAsc(
				ticker, CalendarEventType.EARNINGS, today, today.plusDays(EARNINGS_LOOKAHEAD_DAYS));
		if (upcoming.isEmpty()) {
			return "No upcoming earnings date found within " + EARNINGS_LOOKAHEAD_DAYS + " days.";
		}
		CalendarEvent next = upcoming.get(0);
		StringBuilder sb = new StringBuilder("Next earnings: ").append(next.getEventDate());
		if (next.getEpsEstimate() != null) {
			sb.append(", EPS estimate ").append(next.getEpsEstimate());
		}
		if (next.getEpsActual() != null) {
			sb.append(", last actual EPS ").append(next.getEpsActual());
		}
		if (next.getEpsSurprisePercent() != null) {
			sb.append(String.format(Locale.ROOT, " (%.1f%% surprise)", next.getEpsSurprisePercent()));
		}
		return sb.toString();
	}

	/** Key valuation/profitability/leverage ratios from Finnhub's {@code /stock/metric} (fast-follow
	 * — confirmed available on existing credentials). Best-effort like every other gather step: no key
	 * configured, a rate-limit drop, or a missing field each degrade to "not available" rather than
	 * failing the step or inventing a number. Full financial-<em>statement</em> data (line items via
	 * SEC EDGAR) remains out of scope — see the class javadoc. */
	private String summarizeFinancials(String ticker) {
		if (finnhubApiKey == null || finnhubApiKey.isBlank()) {
			return "No Finnhub API key configured — financial ratios unavailable.";
		}
		String url = "https://finnhub.io/api/v1/stock/metric?symbol=" + ticker + "&metric=all&token=" + finnhubApiKey;
		Optional<String> body = finnhub.get(url);
		if (body.isEmpty()) {
			return "Financial ratios unavailable (rate-limited or no data for this symbol).";
		}
		JsonNode metric = JSON.readTree(body.get()).path("metric");
		if (metric.isMissingNode()) {
			return "Financial ratios unavailable (no data for this symbol).";
		}
		List<String> parts = new ArrayList<>();
		addIfPresent(parts, metric, "peTTM", "P/E (TTM)");
		addIfPresent(parts, metric, "netProfitMarginTTM", "net margin % (TTM)");
		addIfPresent(parts, metric, "roeTTM", "ROE % (TTM)");
		addIfPresent(parts, metric, "totalDebt/totalEquityQuarterly", "debt/equity (quarterly)");
		addIfPresent(parts, metric, "currentRatioQuarterly", "current ratio (quarterly)");
		addIfPresent(parts, metric, "revenueGrowthTTMYoy", "revenue growth % YoY (TTM)");
		addIfPresent(parts, metric, "epsGrowthTTMYoy", "EPS growth % YoY (TTM)");
		addIfPresent(parts, metric, "52WeekHigh", "52-week high");
		addIfPresent(parts, metric, "52WeekLow", "52-week low");
		if (parts.isEmpty()) {
			return "Financial ratios returned no usable fields for this symbol.";
		}
		return "Ratios (no line-item financial statements gathered): " + String.join(", ", parts) + ".";
	}

	private static void addIfPresent(List<String> parts, JsonNode metric, String field, String label) {
		JsonNode v = metric.path(field);
		if (!v.isMissingNode() && !v.isNull()) {
			parts.add(label + " " + v.asString());
		}
	}

	// ---- synthesis (the one paid call) ----

	private String synthesize(String ticker, List<Step> plan, Map<String, String> findings) {
		StringBuilder findingsBlock = new StringBuilder();
		for (Step s : plan) {
			String finding = findings.get(s.id());
			if (finding != null) {
				findingsBlock.append("## ").append(s.label()).append(" (").append(s.dataSource()).append(")\n")
						.append(finding).append("\n\n");
			}
		}
		String prompt = """
				You are Agent 9, Argus's on-demand research analyst. Write a research report on %s based \
				ONLY on the findings below. Do not invent any figure — financial, ratio, or otherwise — \
				that isn't given; if a ratio finding is present use it, but note that full financial \
				statements (line-item revenue/profit/debt/balance-sheet figures) were never gathered, so \
				say so honestly rather than guessing at anything beyond the ratios you were given.

				FINDINGS:
				%s

				Write a markdown report with these sections, in this order: a one-line headline verdict \
				(bullish/bearish/neutral lean, and whether the case reads more long-term or short-term), \
				Sentiment & Momentum, Insider Activity, Macro Backdrop, Financial Ratios (only if that \
				finding is present — omit the section otherwise), Key Risks, and a closing "What wasn't \
				assessed" note (full financial statements and 10-K/10-Q narrative detail weren't gathered). \
				Be specific and cite the findings; say plainly when data was thin or missing rather than padding.
				""".formatted(ticker, findingsBlock);
		try {
			return gateway.escalate(prompt);
		}
		catch (RuntimeException ex) {
			log.warn("Agent 9: synthesis failed for {}: {}", ticker, ex.getMessage());
			return "# Research on " + ticker + "\n\nThe synthesis step failed (" + ex.getMessage()
					+ "). Findings were gathered but no report could be generated — try again shortly.";
		}
	}

	// ---- JSON plumbing ----

	/** One research step. Package-visible for tests. */
	record Step(String id, String label, String dataSource, String why, String status) {
		Step withStatus(String newStatus) {
			return new Step(id, label, dataSource, why, newStatus);
		}
	}

	private static String writePlan(List<Step> plan) {
		return JSON.writeValueAsString(plan);
	}

	private static String writeFindings(Map<String, String> findings) {
		return JSON.writeValueAsString(findings);
	}

	/** Parses a model's step-array reply defensively (strips code fences, extracts the {@code [...]}
	 * span, assigns stable ids), same shape as {@code LogicReviewService.parse}/
	 * {@code MacroKeywordLearningService.parse} — empty list on any failure, never throws. Package-visible
	 * for tests. */
	static List<Step> parseSteps(String raw) {
		if (raw == null) {
			return List.of();
		}
		String s = raw.replace("```json", "").replace("```", "").strip();
		int lb = s.indexOf('['), rb = s.lastIndexOf(']');
		if (lb < 0 || rb <= lb) {
			return List.of();
		}
		List<Step> out = new ArrayList<>();
		try {
			int i = 0;
			for (JsonNode n : JSON.readTree(s.substring(lb, rb + 1))) {
				String label = n.path("label").asString("").trim();
				String dataSource = n.path("dataSource").asString("").trim().toUpperCase(Locale.ROOT);
				String why = n.path("why").asString("").trim();
				if (label.isEmpty() || !DATA_SOURCES.contains(dataSource)) {
					continue; // skip anything that doesn't name a real source — never guess
				}
				out.add(new Step("s" + (++i), label, dataSource, why, "PENDING"));
			}
		}
		catch (RuntimeException ex) {
			log.warn("Agent 9: step-plan JSON parse failed: {}", ex.getMessage());
			return List.of();
		}
		return out;
	}

	/** Package-visible for tests. */
	static List<Step> readPlan(String planJson) {
		if (planJson == null || planJson.isBlank()) {
			return List.of();
		}
		try {
			List<Step> out = new ArrayList<>();
			for (JsonNode n : JSON.readTree(planJson)) {
				out.add(new Step(n.path("id").asString(""), n.path("label").asString(""),
						n.path("dataSource").asString(""), n.path("why").asString(""),
						n.path("status").asString("PENDING")));
			}
			return out;
		}
		catch (RuntimeException ex) {
			return List.of();
		}
	}

	/** The view pushed over WebSocket and returned from the REST endpoints. */
	public record ResearchJobView(Long id, String ticker, String status, List<Step> plan, String report,
			String error, Instant createdAt, Instant updatedAt) {

		static ResearchJobView from(ResearchJob job, List<Step> plan) {
			return new ResearchJobView(job.getId(), job.getTicker(), job.getStatus().name(), plan,
					job.getReport(), job.getError(), job.getCreatedAt(), job.getUpdatedAt());
		}

		static ResearchJobView from(ResearchJob job) {
			return from(job, readPlan(job.getPlan()));
		}
	}
}
