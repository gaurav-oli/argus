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
	private final com.argus.technical.PriceCandleRepository candles;
	private final com.argus.deepanalysis.DeepAnalysisRepository deep;
	private final com.argus.intelligence.KnownUniverse universe;
	private final org.springframework.jdbc.core.JdbcTemplate jdbc;

	/** Fundamentals refresh nightly with an hourly retry: the OLDEST tracked ticker past this means one is stuck. */
	static final Duration FUNDAMENTALS_STALE_AFTER = Duration.ofHours(36);
	/** The morning briefing is daily; past this, a morning was missed. */
	static final Duration BRIEFING_STALE_AFTER = Duration.ofHours(26);

	public FreshnessService(NewsArticleRepository news, SocialPostRepository social, WebMentionRepository web,
			SecFilingRepository sec, RecommendationRepository recommendations, CalendarEventRepository calendar,
			com.argus.technical.PriceCandleRepository candles, com.argus.deepanalysis.DeepAnalysisRepository deep,
			com.argus.intelligence.KnownUniverse universe, org.springframework.jdbc.core.JdbcTemplate jdbc) {
		this.news = news;
		this.social = social;
		this.web = web;
		this.sec = sec;
		this.recommendations = recommendations;
		this.calendar = calendar;
		this.candles = candles;
		this.deep = deep;
		this.universe = universe;
		this.jdbc = jdbc;
	}

	public FreshnessView snapshot() {
		Instant now = Instant.now();
		// Threshold = expected cadence + a generous buffer, so a single skipped cycle isn't "stale".
		// The values live in AgentCadence, shared with the Agents-page pipeline.
		// Filings and calendar are event-driven, not a constant drip — real Form 4 filings and new
		// FOMC/earnings dates genuinely go 2-4 real days between anything NEW some stretches (confirmed
		// against this instance's own ingest history, 2026-10), so their buffer is a multi-day one, not
		// an hours one, or a perfectly healthy quiet period reads as "stuck" every single week.
		List<SourceFreshness> sources = List.of(
				freshness("news", "News (Agent 1)", news::latestIngestedAt, AgentCadence.NEWS.staleAfter(), now),
				freshness("social", "Social (Agent 2)", social::latestIngestedAt, AgentCadence.SOCIAL.staleAfter(), now),
				freshness("internet", "Internet (Agent 3)", web::latestIngestedAt, AgentCadence.INTERNET.staleAfter(), now),
				freshness("filings", "SEC filings (Agent 4)", sec::latestIngestedAt, AgentCadence.FILINGS.staleAfter(), now),
				freshness("recommender", "Recommendations (Agent 5)", recommendations::latestCreatedAt,
						AgentCadence.RECOMMENDER.staleAfter(), now),
				freshness("calendar", "Calendar (Agent 7)", calendar::latestActivityAt, AgentCadence.CALENDAR.staleAfter(), now),
				freshness("technical", "Daily prices (Agent 10)", candles::latestIngestedAt, AgentCadence.TECHNICAL.staleAfter(), now),
				// The OLDEST tracked ticker's snapshot, not the newest: "most recent fetch" hid SMCI/SPCX sitting
				// 3-4 days old behind tickers that did refresh.
				freshness("fundamentals", "Fundamentals, oldest ticker (Agent 12)", this::oldestTrackedFundamentals,
						FUNDAMENTALS_STALE_AFTER, now),
				freshness("deep", "Deep analysis (Agent 11)", deep::latestFinishedAt, AgentCadence.DEEP.staleAfter(), now),
				freshness("briefing", "Morning briefing", this::latestBriefing, BRIEFING_STALE_AFTER, now));
		boolean anyStale = sources.stream().anyMatch(SourceFreshness::stale);
		return new FreshnessView(sources, anyStale);
	}

	private Instant oldestTrackedFundamentals() {
		java.util.Set<String> tracked = universe.knownTickers();
		if (tracked.isEmpty()) {
			return null;
		}
		return jdbc.query("select min(fetched_at) from fundamentals_snapshot where ticker = any(?)",
				ps -> ps.setArray(1, ps.getConnection().createArrayOf("text", tracked.toArray())),
				rs -> rs.next() && rs.getTimestamp(1) != null ? rs.getTimestamp(1).toInstant() : null);
	}

	/** Across everyone — this runs with no signed-in user, where the per-user briefing finder sees nothing. */
	private Instant latestBriefing() {
		return jdbc.query("select max(generated_at) from briefings",
				rs -> rs.next() && rs.getTimestamp(1) != null ? rs.getTimestamp(1).toInstant() : null);
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
