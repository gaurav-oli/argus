package com.argus.ops;

import com.argus.calendar.CalendarEventRepository;
import com.argus.intelligence.NewsArticleRepository;
import com.argus.internet.WebMentionRepository;
import com.argus.recommendation.RecommendationRepository;
import com.argus.sec.SecFilingRepository;
import com.argus.social.SocialPostRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;

/**
 * Data-freshness monitor for the Ops dashboard (Story 9.7, FR-29). For each agent data source it reads
 * the most-recent update time and flags it stale when it exceeds a per-source threshold (cadence +
 * buffer). Backup-status (the other half of 9.7) needs the external SSD and is deferred to the Mini.
 */
@Service
public class FreshnessService {

	private final NewsArticleRepository news;
	private final SocialPostRepository social;
	private final WebMentionRepository web;
	private final SecFilingRepository sec;
	private final RecommendationRepository recommendations;
	private final CalendarEventRepository calendar;

	public FreshnessService(NewsArticleRepository news, SocialPostRepository social, WebMentionRepository web,
			SecFilingRepository sec, RecommendationRepository recommendations, CalendarEventRepository calendar) {
		this.news = news;
		this.social = social;
		this.web = web;
		this.sec = sec;
		this.recommendations = recommendations;
		this.calendar = calendar;
	}

	public FreshnessView snapshot() {
		Instant now = Instant.now();
		// Threshold = expected cadence + a generous buffer, so a single skipped cycle isn't "stale".
		List<SourceFreshness> sources = List.of(
				freshness("news", "News (Agent 1)", news::latestIngestedAt, Duration.ofMinutes(30), now),
				freshness("social", "Social (Agent 2)", social::latestIngestedAt, Duration.ofHours(1), now),
				freshness("internet", "Internet (Agent 3)", web::latestIngestedAt, Duration.ofHours(12), now),
				freshness("filings", "SEC filings (Agent 4)", sec::latestIngestedAt, Duration.ofHours(12), now),
				freshness("recommender", "Recommendations (Agent 5)", recommendations::latestCreatedAt,
						Duration.ofHours(12), now),
				freshness("calendar", "Calendar (Agent 7)", calendar::latestIngestedAt, Duration.ofHours(36), now));
		boolean anyStale = sources.stream().anyMatch(SourceFreshness::stale);
		return new FreshnessView(sources, anyStale);
	}

	private static SourceFreshness freshness(String key, String label, Supplier<Instant> latest,
			Duration threshold, Instant now) {
		Instant last = latest.get();
		Long ageMinutes = last == null ? null : Duration.between(last, now).toMinutes();
		return new SourceFreshness(key, label, last, ageMinutes, isStale(last, threshold, now), threshold.toMinutes());
	}

	/** Stale when never updated, or older than the threshold. Pure — unit-tested. */
	static boolean isStale(Instant last, Duration threshold, Instant now) {
		return last == null || Duration.between(last, now).compareTo(threshold) > 0;
	}
}
