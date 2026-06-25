package com.argus.ops;

import com.argus.calendar.CalendarEventRepository;
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
 * Assembles a live per-agent status snapshot for the Agents dashboard (Epic 9, Story 9.1) by
 * aggregating each agent's output table — count + most-recent timestamp — and annotating any
 * data-source dependency (e.g. the Finnhub key). Read-only; computed on demand.
 */
@Service
public class AgentStatusService {

	private final NewsArticleRepository news;
	private final SourceCredibilityRepository credibility;
	private final StrangerAlertRepository stranger;
	private final RecommendationRepository recommendations;
	private final CalendarEventRepository calendar;
	private final boolean finnhubEnabled;

	public AgentStatusService(NewsArticleRepository news, SourceCredibilityRepository credibility,
			StrangerAlertRepository stranger, RecommendationRepository recommendations,
			CalendarEventRepository calendar, @Value("${argus.finnhub.api-key:}") String finnhubKey) {
		this.news = news;
		this.credibility = credibility;
		this.stranger = stranger;
		this.recommendations = recommendations;
		this.calendar = calendar;
		this.finnhubEnabled = StringUtils.hasText(finnhubKey);
	}

	/** The current status of every agent in the fleet, in display order. */
	public List<AgentStatusView> snapshot() {
		return List.of(
				agent("news", "Agent 1", "News Intelligence",
						"Ingests market news from Finnhub, GDELT and RSS, dedupes and tags tickers.",
						news.count(), "articles", news.latestIngestedAt(), "≤5 min · market hours",
						finnhubEnabled ? "Finnhub + GDELT + RSS sources live"
								: "Running on free GDELT + RSS (no Finnhub key)"),
				agent("sentiment", "Agent 2", "Sentiment & Relevance",
						"Scores each article's sentiment and relevance to your holdings.",
						news.countAnalyzed(), "analyzed", news.latestAnalyzedAt(),
						"with each news batch", null),
				agent("credibility", "Agent 3", "Source Credibility",
						"Maintains a trust score per news source to weight signals.",
						credibility.count(), "sources scored", credibility.latestUpdatedAt(),
						"continuous", null),
				agent("stranger", "Agent 4", "Stranger Danger",
						"Watches for pump-and-dump chatter on tickers you don't hold.",
						stranger.count(), "alerts raised", stranger.latestDetectedAt(), "every 60s", null),
				agent("recommender", "Agent 5", "Recommender",
						"Fuses agent signals into auditable, probability-scored recommendations.",
						recommendations.count(), "recommendations", recommendations.latestCreatedAt(),
						"every 6h", null),
				agent("calendar", "Agent 7", "Economic Calendar",
						"Tracks earnings and economic events, flagging pre-event quiet periods.",
						calendar.count(), "events tracked", calendar.latestIngestedAt(), "daily · 6am ET",
						finnhubEnabled ? null : "Earnings calendar needs a Finnhub key"));
	}

	private static AgentStatusView agent(String id, String code, String name, String description,
			long captured, String captureLabel, Instant lastActivity, String schedule, String note) {
		String status = captured > 0 ? "ACTIVE" : "IDLE";
		return new AgentStatusView(id, code, name, description, status, captured, captureLabel,
				lastActivity, schedule, note);
	}
}
