package com.argus.ops;

import com.argus.calendar.CalendarEventRepository;
import com.argus.cost.BudgetStatus;
import com.argus.cost.CostGovernor;
import com.argus.cost.CostRecorder;
import com.argus.intelligence.NewsArticleRepository;
import com.argus.intelligence.SourceCredibilityRepository;
import com.argus.intelligence.StrangerAlertRepository;
import com.argus.internet.WebMentionRepository;
import com.argus.recommendation.RecommendationRepository;
import com.argus.sec.SecFilingRepository;
import com.argus.social.SocialPostRepository;
import java.time.Instant;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * Per-agent status for the Agents dashboard (Epic 9, Story 9.1), aligned to the architecture's
 * 7-agent roster. All seven now run on real data: Agent 1 (News — which also owns the Source
 * Credibility Engine and the Stranger Danger watch), Agent 2 (Social), Agent 3 (Internet),
 * Agent 4 (SEC filings), Agent 5 (Recommender), Agent 6 (Cost Governor — budget governance with
 * auto-switch), and Agent 7 (Calendar).
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
	private final boolean finnhubEnabled;
	private final boolean redditEnabled;

	public AgentStatusService(NewsArticleRepository news, SourceCredibilityRepository credibility,
			StrangerAlertRepository stranger, RecommendationRepository recommendations,
			CalendarEventRepository calendar, SocialPostRepository social, SecFilingRepository sec,
			WebMentionRepository web, CostGovernor costGovernor,
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
						finnhubEnabled ? null : "Earnings calendar needs a Finnhub key"));
	}

	private static AgentStatusView active(String id, String code, String name, String description,
			long captured, String captureLabel, Instant lastActivity, String schedule, String note) {
		return new AgentStatusView(id, code, name, description, captured > 0 ? "ACTIVE" : "IDLE", captured,
				captureLabel, lastActivity, schedule, note, null);
	}

	private AgentStatusView costGovernor() {
		BudgetStatus b = costGovernor.status();
		String note = String.format("$%.2f of $%.0f this month (%.0f%%) · %s", b.spentUsd(), b.budgetUsd(),
				b.percentUsed(), b.band()) + (b.paidCallsBlocked() ? " · auto-switched to local" : "");
		return new AgentStatusView("cost", "Agent 6", "Cost Governor",
				"Tracks paid-API (Haiku) spend against the monthly budget; warns at 70/80%, and at 95% "
						+ "auto-switches escalations to the local model.",
				"ACTIVE", b.paidCalls(), "paid calls", null, "continuous", note, null);
	}

	private static AgentStatusView planned(String id, String code, String name, String description, String phase) {
		return new AgentStatusView(id, code, name, description, "PLANNED", 0, "", null, "—", null, phase);
	}
}
