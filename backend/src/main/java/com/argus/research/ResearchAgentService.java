package com.argus.research;

import com.argus.calendar.CalendarEvent;
import com.argus.calendar.CalendarEventRepository;
import com.argus.calendar.CalendarEventType;
import com.argus.common.BadRequestException;
import com.argus.common.LivePushService;
import com.argus.deepanalysis.DeepAnalysis;
import com.argus.deepanalysis.DeepAnalysisRunner;
import com.argus.deepanalysis.DeepAnalysisService;
import com.argus.fundamentals.Fundamentals;
import com.argus.fundamentals.FundamentalsService;
import com.argus.intelligence.MacroRelevanceTagger;
import com.argus.intelligence.NewsArticle;
import com.argus.intelligence.NewsArticleRepository;
import com.argus.internet.WebMention;
import com.argus.internet.WebMentionRepository;
import com.argus.model.ModelGateway;
import com.argus.model.ModelTier;
import com.argus.sec.SecFiling;
import com.argus.sec.SecFilingRepository;
import com.argus.social.SocialPost;
import com.argus.social.SocialPostRepository;
import com.argus.technical.ChartStudy;
import com.argus.technical.ChartStudyService;
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
 * sources the other agents use ({@link #gather}), checks after each step whether the remaining plan
 * should change ({@link #replanCheck}), then synthesizes a report ({@link #synthesize}).
 *
 * <p>Cost is bounded regardless of how many replans happen: the plan and every replan check use the
 * local model ({@code ModelGateway.generate(..., BIG)}, free); only the single final synthesis call
 * uses {@code escalate()} (paid Haiku) — the same "rare, valuable, user-initiated" idiom
 * {@code ConversationService}'s "deeper analysis" already uses.
 *
 * <p>Besides the news/macro/crowd/insider/web/earnings sources, it draws on the analysis agents:
 * <b>FINANCIALS</b> is Agent 12's fundamentals (quarterly statements, growth and margin trends, earnings
 * surprises, analyst consensus, valuation against peers); <b>TECHNICAL</b> is Agent 10's chart study
 * (candlestick patterns, volume, trend, support/resistance, relative strength); <b>DEEP</b> is Agent 11's
 * latest verdict. Agent 11 takes minutes to hours, so this step never waits for it: it reads the latest
 * finished analysis, or — if there is none — queues one and says so honestly. The synthesis prompt stays
 * strict about not inventing anything that wasn't gathered.
 */
@Service
public class ResearchAgentService {

	private static final Logger log = LoggerFactory.getLogger(ResearchAgentService.class);
	private static final JsonMapper JSON = JsonMapper.builder().build();
	private static final Pattern TICKER_PATTERN = Pattern.compile("^[A-Z]{1,6}(\\.[A-Z])?$");
	private static final List<String> DATA_SOURCES =
			List.of("NEWS", "MACRO", "SOCIAL", "INSIDER", "WEB", "EARNINGS", "FINANCIALS", "TECHNICAL", "DEEP");
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
	private final ChartStudyService charts;
	private final FundamentalsService fundamentals;
	private final DeepAnalysisService deepAnalyses;
	private final DeepAnalysisRunner deepRunner;
	private final ExecutorService executor;

	public ResearchAgentService(ResearchJobRepository jobs, NewsArticleRepository news,
			SocialPostRepository social, SecFilingRepository sec, WebMentionRepository web,
			CalendarEventRepository calendar, ModelGateway gateway, LivePushService livePush,
			ResearchJobProperties props, ChartStudyService charts, FundamentalsService fundamentals,
			DeepAnalysisService deepAnalyses, DeepAnalysisRunner deepRunner) {
		this.jobs = jobs;
		this.news = news;
		this.social = social;
		this.sec = sec;
		this.web = web;
		this.calendar = calendar;
		this.gateway = gateway;
		this.livePush = livePush;
		this.props = props;
		this.charts = charts;
		this.fundamentals = fundamentals;
		this.deepAnalyses = deepAnalyses;
		this.deepRunner = deepRunner;
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
				- FINANCIALS: full fundamental analysis — quarterly income statements, revenue/margin trends, earnings surprises, analyst consensus, valuation vs peers
				- TECHNICAL: the chart study — trend vs the 20/50/200-day averages, momentum, volume, candlestick patterns, support/resistance, strength vs the market
				- DEEP: Agent 11's most recent deep-analysis verdict (worth buying / wait / not worth buying, and for how long)

				Propose an ordered research plan, 3-9 steps, each using exactly one data source.
				Respond with ONLY a JSON array, no prose: \
				[{"label":"short step name","dataSource":"NEWS|MACRO|SOCIAL|INSIDER|WEB|EARNINGS|FINANCIALS|TECHNICAL|DEEP","why":"one sentence"}]
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
		steps.add(new Step("s7", "Fundamentals", "FINANCIALS", "Growth, margins, balance sheet, earnings track record, analysts, valuation.", "PENDING"));
		steps.add(new Step("s8", "Chart study", "TECHNICAL", "Trend, candlesticks, volume, support/resistance, relative strength.", "PENDING"));
		steps.add(new Step("s9", "Agent 11's deep analysis", "DEEP", "The deep analyst's latest verdict and reasoning.", "PENDING"));
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
				[{"label":"...","dataSource":"NEWS|MACRO|SOCIAL|INSIDER|WEB|EARNINGS|FINANCIALS|TECHNICAL|DEEP","why":"..."}]
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
				case "TECHNICAL" -> summarizeChart(ticker);
				case "DEEP" -> summarizeDeep(ticker);
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

	/**
	 * Agent 12's fundamentals — quarterly statements, growth and margin trends, earnings surprises, analyst
	 * consensus and valuation against peers — refreshed live if the stored snapshot is more than a day old
	 * (this is a slow, user-initiated job, so the ~10 Finnhub calls are affordable). Best-effort like every
	 * gather step: no key, a rate-limit drop, or an ETF each degrade to an honest line, never a guess.
	 */
	private String summarizeFinancials(String ticker) {
		Optional<Fundamentals> f = fundamentals.getOrRefresh(ticker, Duration.ofHours(24));
		if (f.isEmpty()) {
			return "Financial data unavailable (no Finnhub key, rate-limited, or the symbol is not covered).";
		}
		return f.get().render();
	}

	/** Agent 10's chart study, from stored candles. */
	private String summarizeChart(String ticker) {
		Optional<ChartStudy> s = charts.studyFor(ticker);
		return s.map(ChartStudy::render).orElse("No chart study: not enough daily price history is stored for this symbol yet.");
	}

	/**
	 * Agent 11's latest verdict. Never waits for it (a full analysis takes minutes to hours): reads the newest
	 * finished analysis, flagging its age, or — if there is none — queues one and says plainly that it is not
	 * part of this report.
	 */
	private String summarizeDeep(String ticker) {
		Optional<DeepAnalysis> latest = deepAnalyses.latestDone(ticker);
		if (latest.isEmpty()) {
			try {
				deepRunner.enqueue(ticker, "RESEARCH");
			}
			catch (RuntimeException ex) {
				log.debug("Agent 9: could not queue a deep analysis for {}: {}", ticker, ex.getMessage());
			}
			return "Agent 11 has not analysed this ticker yet. A deep analysis has been queued (it can take a while) and is NOT part of this report.";
		}
		DeepAnalysis d = latest.get();
		long ageDays = Duration.between(d.getFinishedAt(), Instant.now()).toDays();
		StringBuilder sb = new StringBuilder(String.format(Locale.ROOT, "Agent 11's verdict (analysed %d day(s) ago%s): %s%s, conviction %s/100.%n",
				ageDays, d.getExpiresAt() != null && d.getExpiresAt().isBefore(Instant.now()) ? ", now stale" : "",
				d.getVerdict() == null ? "no verdict" : d.getVerdict().label(),
				d.getHoldDays() == null ? "" : " — hold about " + d.getHoldDays() + " days", d.getConviction()));
		if (d.getHeadline() != null) sb.append("Headline: ").append(d.getHeadline()).append('\n');
		if (d.getThesis() != null) sb.append("Thesis: ").append(d.getThesis()).append('\n');
		if (d.getBullCase() != null) sb.append("Bull case: ").append(d.getBullCase()).append('\n');
		if (d.getBearCase() != null) sb.append("Bear case: ").append(d.getBearCase()).append('\n');
		if (d.getRisks() != null && !d.getRisks().isBlank()) sb.append("Risks: ").append(d.getRisks().replace('\n', ';')).append('\n');
		if (d.getInvalidation() != null && !d.getInvalidation().isBlank()) sb.append("What would change its mind: ").append(d.getInvalidation()).append('\n');
		if (d.getGuardNotes() != null && !d.getGuardNotes().isBlank()) sb.append("Guardrail notes: ").append(d.getGuardNotes().replace('\n', ';')).append('\n');
		return sb.toString();
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
				ONLY on the findings below. Do not invent any figure, ratio, level or event that isn't given. \
				Where a finding says data was unavailable, thin or missing, say so plainly rather than padding. \
				Some findings come from Argus's other analysis agents (the chart study, the fundamentals, and \
				Agent 11's deep-analysis verdict): cite them as such, and if Agent 11's verdict is stale or \
				absent say so.

				FINDINGS:
				%s

				Write a markdown report with these sections, in this order: a one-line headline verdict \
				(bullish/bearish/neutral lean, whether it reads as worth buying, and if so short-, medium- or \
				long-term — reconcile it explicitly with Agent 11's verdict when one exists), Chart & Technicals \
				(only if that finding is present), Fundamentals (only if present), Sentiment & Momentum, \
				Insider Activity, Macro Backdrop, Agent 11's Deep Analysis (only if present), Key Risks, and a \
				closing "What wasn't assessed" note (10-K/10-Q narrative, competitive positioning and anything \
				marked unavailable above). Be specific and cite the findings.
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
