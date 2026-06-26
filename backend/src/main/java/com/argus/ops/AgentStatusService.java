package com.argus.ops;

import com.argus.calendar.CalendarEventRepository;
import com.argus.cost.CostRecorder;
import com.argus.intelligence.NewsArticleRepository;
import com.argus.intelligence.SourceCredibilityRepository;
import com.argus.intelligence.StrangerAlertRepository;
import com.argus.recommendation.RecommendationRepository;
import java.time.Instant;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * Per-agent status for the Agents dashboard (Epic 9, Story 9.1), aligned to the architecture's
 * 7-agent roster. Phase-1 agents run on real data: Agent 1 (News — which also owns the Source
 * Credibility Engine and the Stranger Danger watch), Agent 5 (Recommender), Agent 7 (Calendar).
 * Agent 6 (Cost Governor) ships as an MVP subsystem (cost tracking only). Agents 2/3/4 and full
 * Agent 6 are planned (Phase 2/3) and shown as such — not yet built.
 */
@Service
public class AgentStatusService {

	private final NewsArticleRepository news;
	private final SourceCredibilityRepository credibility;
	private final StrangerAlertRepository stranger;
	private final RecommendationRepository recommendations;
	private final CalendarEventRepository calendar;
	private final CostRecorder cost;
	private final boolean finnhubEnabled;

	public AgentStatusService(NewsArticleRepository news, SourceCredibilityRepository credibility,
			StrangerAlertRepository stranger, RecommendationRepository recommendations,
			CalendarEventRepository calendar, CostRecorder cost,
			@Value("${argus.finnhub.api-key:}") String finnhubKey) {
		this.news = news;
		this.credibility = credibility;
		this.stranger = stranger;
		this.recommendations = recommendations;
		this.calendar = calendar;
		this.cost = cost;
		this.finnhubEnabled = StringUtils.hasText(finnhubKey);
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
				planned("social", "Agent 2", "Social Media Intelligence",
						"StockTwits + Reddit sentiment, every 2–5 min.", "Phase 2"),
				planned("internet", "Agent 3", "Internet Intelligence",
						"Broad web monitoring beyond curated feeds.", "Phase 3"),
				planned("filings", "Agent 4", "Financial Reports",
						"SEC filings + insider Form 4 parsing.", "Phase 2"),
				active("recommender", "Agent 5", "Recommender",
						"The only agent that recommends — fuses agent signals into auditable, "
								+ "probability-scored forecasts via a graduation state machine.",
						recommendations.count(), "recommendations", recommendations.latestCreatedAt(), "every 6h",
						null),
				partial("cost", "Agent 6", "Cost Governor",
						"Tracks paid-API (Haiku) spend; full budget governance + auto-switch is Phase 2 (Epic 10).",
						String.format("$%.4f spent this run", cost.totalUsd())),
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

	private static AgentStatusView partial(String id, String code, String name, String description, String note) {
		return new AgentStatusView(id, code, name, description, "PARTIAL", 0, "", null, "continuous", note,
				"MVP subsystem");
	}

	private static AgentStatusView planned(String id, String code, String name, String description, String phase) {
		return new AgentStatusView(id, code, name, description, "PLANNED", 0, "", null, "—", null, phase);
	}
}
