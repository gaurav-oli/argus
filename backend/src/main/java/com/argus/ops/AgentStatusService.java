package com.argus.ops;

import com.argus.calendar.CalendarEventRepository;
import com.argus.cost.BudgetStatus;
import com.argus.cost.CostGovernor;
import com.argus.cost.CostRecorder;
import com.argus.deepanalysis.DeepAnalysis;
import com.argus.deepanalysis.DeepAnalysisRepository;
import com.argus.deepanalysis.DeepScorecardService;
import com.argus.filings.FilingDigestRepository;
import com.argus.fundamentals.FundamentalsSnapshotRepository;
import com.argus.learning.LearnedRule;
import com.argus.learning.LearnedRuleRepository;
import com.argus.learning.LearningReportRepository;
import com.argus.intelligence.MacroKeywordRepository;
import com.argus.intelligence.MacroRelevanceTagger;
import com.argus.intelligence.NewsArticleRepository;
import com.argus.intelligence.SourceCredibilityRepository;
import com.argus.intelligence.StrangerAlertRepository;
import com.argus.internet.WebMentionRepository;
import com.argus.recommendation.RecommendationRepository;
import com.argus.strategy.AcademicStrategy;
import com.argus.strategy.AcademicStrategyRepository;
import com.argus.strategy.StrategyUniverseRepository;
import com.argus.research.ResearchJob;
import com.argus.research.ResearchJobRepository;
import com.argus.sec.SecFilingRepository;
import com.argus.social.SocialPostRepository;
import com.argus.technical.PriceCandleRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * Per-agent status for the Agents dashboard (Epic 9, Story 9.1). All run on real data: Agent 1
 * (News — which also owns the Source Credibility Engine and the Stranger Danger watch), Agent 2
 * (Social), Agent 3 (Internet), Agent 4 (SEC filings), Agent 5 (Recommender), Agent 6 (Cost
 * Governor — budget governance with auto-switch), Agent 7 (Calendar), Agent 8 (Macro/political
 * news — no source of its own; it tags the same articles Agent 1 already ingests, so its coverage
 * is bounded by Agent 1's sources), Agent 9 (on-demand research), and Agent 10 (Technical Analysis
 * + cause-of-move classification — one card covering two signal identities, {@code
 * agent-10-technical} and {@code agent-11-cause}, kept distinct for tuning purposes but not
 * fragmented into two UI cards). The analysis agents follow: Agent 11 (Deep Analyst), Agent 12
 * (Fundamentals), Agent 13 (Trade Learner), Agent 14 (Filings Reader) and Agent 15 (Academic Strategies).
 */
@Service
public class AgentStatusService {

	private final NewsArticleRepository news;
	private final SourceCredibilityRepository credibility;
	private final StrangerAlertRepository stranger;
	private final RecommendationRepository recommendations;
	private final CalendarEventRepository calendar;
	private final SocialPostRepository social;
	private final SecFilingRepository sec;
	private final WebMentionRepository web;
	private final CostGovernor costGovernor;
	private final MacroKeywordRepository macroKeywords;
	private final ResearchJobRepository research;
	private final PriceCandleRepository candles;
	private final DeepAnalysisRepository deep;
	private final DeepScorecardService scorecard;
	private final FundamentalsSnapshotRepository fundamentalSnapshots;
	private final LearnedRuleRepository learnedRules;
	private final LearningReportRepository learningReports;
	private final FilingDigestRepository filingDigests;
	private final AcademicStrategyRepository academicStrategies;
	private final StrategyUniverseRepository rankingUniverse;
	private final boolean finnhubEnabled;
	private final boolean redditEnabled;

	public AgentStatusService(NewsArticleRepository news, SourceCredibilityRepository credibility,
			StrangerAlertRepository stranger, RecommendationRepository recommendations,
			CalendarEventRepository calendar, SocialPostRepository social, SecFilingRepository sec,
			WebMentionRepository web, CostGovernor costGovernor, MacroKeywordRepository macroKeywords,
			ResearchJobRepository research, PriceCandleRepository candles, DeepAnalysisRepository deep,
			DeepScorecardService scorecard, FundamentalsSnapshotRepository fundamentalSnapshots, LearnedRuleRepository learnedRules,
			LearningReportRepository learningReports, FilingDigestRepository filingDigests,
			AcademicStrategyRepository academicStrategies, StrategyUniverseRepository rankingUniverse,
			@Value("${argus.finnhub.api-key:}") String finnhubKey,
			@Value("${argus.reddit.client-id:}") String redditClientId) {
		this.news = news;
		this.credibility = credibility;
		this.stranger = stranger;
		this.recommendations = recommendations;
		this.calendar = calendar;
		this.social = social;
		this.sec = sec;
		this.web = web;
		this.costGovernor = costGovernor;
		this.macroKeywords = macroKeywords;
		this.research = research;
		this.candles = candles;
		this.deep = deep;
		this.scorecard = scorecard;
		this.fundamentalSnapshots = fundamentalSnapshots;
		this.learnedRules = learnedRules;
		this.learningReports = learningReports;
		this.filingDigests = filingDigests;
		this.academicStrategies = academicStrategies;
		this.rankingUniverse = rankingUniverse;
		this.finnhubEnabled = StringUtils.hasText(finnhubKey);
		this.redditEnabled = StringUtils.hasText(redditClientId);
	}

	/** The current status of every agent in the fleet, in roster order. */
	public List<AgentStatusView> snapshot() {
		String agent1Note = "Source credibility: " + credibility.count() + " scored · Stranger Danger: "
				+ stranger.count() + " alert" + (stranger.count() == 1 ? "" : "s")
				+ (finnhubEnabled ? "" : " · no Finnhub key (GDELT + RSS only)");

		return List.of(
				active("news", "Agent 1", "News Intelligence",
						"Ingests market news (Finnhub/GDELT/RSS), tags ticker relevance, scores source "
								+ "credibility, and runs the Stranger Danger pump-and-dump watch.",
						news.count(), "articles", news.latestIngestedAt(), "≤5 min · market hours", agent1Note),
				active("social", "Agent 2", "Social Media Intelligence",
						"Tracks crowd sentiment on your holdings from StockTwits (and Reddit when keyed), "
								+ "tagging each post bullish/bearish.",
						social.count(), "posts", social.latestIngestedAt(), "≤10 min",
						redditEnabled ? "StockTwits + Reddit live" : "StockTwits live · Reddit needs API keys"),
				active("internet", "Agent 3", "Internet Intelligence",
						"Gauges broad public attention on your holdings — Hacker News discussion + Wikipedia "
								+ "pageview spikes — beyond the curated feeds.",
						web.count(), "web mentions", web.latestIngestedAt(), "every 6h", null),
				active("filings", "Agent 4", "Financial Reports",
						"Watches SEC EDGAR for insider activity (Form 4) on your holdings — open-market "
								+ "purchases vs sales — and feeds Agent 5.",
						sec.count(), "filings", sec.latestIngestedAt(), "every 6h", null),
				active("recommender", "Agent 5", "Recommender",
						"The only agent that recommends — fuses agent signals into auditable, "
								+ "probability-scored forecasts via a graduation state machine.",
						recommendations.count(), "recommendations", recommendations.latestCreatedAt(), "every 6h",
						null),
				costGovernor(),
				active("calendar", "Agent 7", "Economic Calendar",
						"Tracks earnings, Fed/CPI/jobs/GDP, ex-dividend and lock-up dates; flags pre-event quiet periods.",
						calendar.count(), "events tracked", calendar.latestIngestedAt(), "daily · 6am ET",
						finnhubEnabled ? null : "Earnings calendar needs a Finnhub key"),
				active("macro", "Agent 8", "Macro / Political News",
						"Tags tariff/Fed/currency-policy stories that move every held ticker at once, from the "
								+ "same feed Agent 1 ingests — no ticker mention required, so nothing gets dropped "
								+ "for naming no specific stock.",
						news.countByTag(MacroRelevanceTagger.MACRO_TAG), "macro articles",
						news.latestIngestedAtForTag(MacroRelevanceTagger.MACRO_TAG), "same cadence as Agent 1",
						agent8Note()),
				active("research", "Agent 9", "On-Demand Research",
						"Only works when asked: name a ticker and it plans its own research, gathers news, "
								+ "macro, social, insider, web, and earnings data, revises the plan mid-run if it "
								+ "learns something new, then writes a long-term/short-term analysis.",
						research.countByStatus(ResearchJob.Status.DONE), "reports completed",
						null, "on demand", agent9Note()),
				active("technical", "Agent 10", "Chart Reader",
						"Reads the daily chart like a technician: trend against the 20/50/200-day averages, momentum "
								+ "(RSI, MACD, Bollinger), volume accumulation, candlestick patterns in context, support and "
								+ "resistance, and strength against the S&P 500 — plus a cause-of-move read on real drawdowns.",
						candles.count(), "candles stored", candles.latestIngestedAt(), "daily · after close",
						"Candles from Yahoo (keyless), Alpha Vantage fallback"),
				active("deep", "Agent 11", "Deep Analyst",
						"The slow thinker. For each stock it pulls every other agent's evidence, runs four specialists "
								+ "(chart, fundamentals, catalysts, macro) and a skeptic on the local model, then the PM verdict "
								+ "(Claude Haiku, local fallback): worth buying / wait / not worth buying, and for how long. "
								+ "Verdicts are guard-capped, tracked against the S&P 500 and flagged when the thesis breaks.",
						deep.countByStatus(DeepAnalysis.Status.DONE), "verdicts", deep.latestFinishedAt(),
						"nightly 1am · on demand · thesis check hourly", agent11Note()),
				active("fundamentals", "Agent 12", "Fundamentals",
						"Real fundamental analysis from Finnhub: growth and margin trends, balance sheet, earnings "
								+ "surprises, analyst consensus, valuation against peers (P/E, P/S, EV/EBITDA) and a reverse DCF "
								+ "that asks what growth today's price assumes.",
						fundamentalSnapshots.count(), "companies analysed", fundamentalSnapshots.latestFetchedAt(), "daily",
						finnhubEnabled ? null : "Needs a Finnhub key (unset — Agent 12 is inactive)"),
				active("learner", "Agent 13", "Trade Learner",
						"Studies the paper trades that won and lost, finds the situations where Argus is reliably wrong "
								+ "(or right), holds each candidate rule out on unseen trades, and feeds the survivors back to "
								+ "the Recommender, the Investor and Agent 11.",
						learnedRules.countByStatus(LearnedRule.Status.ACTIVE), "active rules",
						learningReports.findFirstByOrderByCreatedAtDesc().map(r -> r.getCreatedAt()).orElse(null), "nightly 3:30am",
						agent13Note()),
				active("filings-reader", "Agent 14", "Filings Reader",
						"Reads the company's own words from SEC EDGAR — earnings releases (8-K) and 10-Q/10-K MD&A — "
								+ "for guidance, tone, going-concern and new-risk language. Every figure is verified against "
								+ "the filing text before it is trusted.",
						filingDigests.count(), "filings digested", filingDigests.latestCreatedAt(), "every 6h", null),
				active("strategies", "Agent 15", "Academic Strategies",
						"Holds the published return-predictor literature as a library — each strategy's exact rule, its "
								+ "original paper and the authors' own replication grade — computes the ones Argus has data "
								+ "for across a 500-name ranking universe, and only lets a strategy influence a call once it "
								+ "has survived a chronological hold-out backtest on Argus's own history.",
						academicStrategies.countByStatus(AcademicStrategy.Status.ACTIVE.name()), "validated strategies",
						academicStrategies.lastImportedAt(), "scored daily · re-validated weekly", agent15Note()));
	}

	/** Most of the library is not usable here, and saying so is the point — the alternative is false confidence. */
	private String agent15Note() {
		long total = academicStrategies.count();
		if (total == 0) {
			return "Library not imported yet";
		}
		long computable = academicStrategies.findByComputableTrue().size();
		long active = academicStrategies.countByStatus(AcademicStrategy.Status.ACTIVE.name());
		long placebos = academicStrategies.countByKind(AcademicStrategy.Kind.PLACEBO.name());
		return total + " published signals (" + placebos + " known placebos) · " + computable + " computable here · "
				+ active + " passed hold-out validation · ranking " + rankingUniverse.countByActiveTrue() + " names";
	}

	private String agent11Note() {
		long running = deep.countByStatus(DeepAnalysis.Status.RUNNING);
		long queued = deep.countByStatus(DeepAnalysis.Status.QUEUED);
		String work = running + queued == 0 ? "Idle" : (running > 0 ? "Analysing now" : "Queued") + " (" + queued + " waiting)";
		try {
			var s = scorecard.summary();
			return work + " · " + s.totalVerdicts() + " verdicts scored against the S&P 500";
		}
		catch (RuntimeException ex) {
			return work;
		}
	}

	private String agent13Note() {
		long proposed = learnedRules.countByStatus(LearnedRule.Status.PROPOSED);
		return proposed + " rule(s) awaiting hold-out validation";
	}

	private static AgentStatusView active(String id, String code, String name, String description,
			long captured, String captureLabel, Instant lastActivity, String schedule, String note) {
		Optional<AgentCadence> cadence = AgentCadence.forAgent(id);
		return new AgentStatusView(id, code, name, description, captured > 0 ? "ACTIVE" : "IDLE", captured,
				captureLabel, lastActivity, schedule, note, null,
				cadence.map(c -> (int) c.interval().toMinutes()).orElse(null),
				cadence.map(c -> (int) c.staleAfter().toMinutes()).orElse(null));
	}

	/** Agent 8's keyword list is DB-backed and self-updating (MacroKeywordLearningService) — surface that. */
	private String agent8Note() {
		long learned = macroKeywords.countBySource("learned");
		return learned > 0
				? "Feeds agent-8-macro · " + learned + " keyword(s) self-learned from missed stories"
				: "Feeds agent-8-macro · keyword list self-updates weekly from missed stories";
	}

	/** Whether a job is currently in flight, surfaced as the note since "on demand" agents don't have a
	 * meaningful last-ingested timestamp the way scheduled agents do. */
	private String agent9Note() {
		long running = research.countByStatusIn(
				List.of(ResearchJob.Status.PLANNING, ResearchJob.Status.RESEARCHING,
						ResearchJob.Status.REVISING_PLAN, ResearchJob.Status.SYNTHESIZING));
		return running > 0 ? running + " job(s) in progress right now" : "Idle — waiting to be asked";
	}

	private AgentStatusView costGovernor() {
		BudgetStatus b = costGovernor.status();
		String note = String.format("$%.2f of $%.0f this month (%.0f%%) · %s", b.spentUsd(), b.budgetUsd(),
				b.percentUsed(), b.band()) + (b.paidCallsBlocked() ? " · auto-switched to local" : "");
		return new AgentStatusView("cost", "Agent 6", "Cost Governor",
				"Tracks paid-API (Haiku) spend against the monthly budget; warns at 70/80%, and at 95% "
						+ "auto-switches escalations to the local model.",
				"ACTIVE", b.paidCalls(), "paid calls", null, "continuous", note, null, null, null);
	}

	private static AgentStatusView planned(String id, String code, String name, String description, String phase) {
		return new AgentStatusView(id, code, name, description, "PLANNED", 0, "", null, "—", null, phase, null, null);
	}
}
