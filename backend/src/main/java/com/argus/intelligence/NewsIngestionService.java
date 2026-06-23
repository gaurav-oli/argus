package com.argus.intelligence;

import com.argus.agent.AgentEventPublisher;
import com.argus.marketdata.MarketClock;
import com.argus.portfolio.Position;
import com.argus.portfolio.PositionRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Agent 1's news-ingestion pipeline (Story 4.1, FR-8). On a cadence-gated schedule — ≤5 min during
 * regular US market hours, ≤15 min off-hours — it pulls from every configured {@link NewsSource},
 * deduplicates against what's already stored, tags each new article with the held tickers it
 * mentions, persists it, and emits a {@code news.article.ingested} event so downstream stages
 * (sentiment, Story 4.2) can react. A single source failing never aborts the cycle.
 */
@Service
public class NewsIngestionService {

	/** Stream carrying newly ingested articles to downstream intelligence stages (Story 4.2). */
	public static final String STREAM_KEY = "argus:stream:news";
	static final String EVENT_INGESTED = "news.article.ingested";

	private static final Logger log = LoggerFactory.getLogger(NewsIngestionService.class);

	private final List<NewsSource> sources;
	private final NewsArticleRepository articles;
	private final TickerRelevanceTagger tagger;
	private final PositionRepository positions;
	private final MarketClock clock;
	private final AgentEventPublisher events;
	private final NewsIngestionProperties props;

	private volatile Instant lastCycle;

	public NewsIngestionService(List<NewsSource> sources, NewsArticleRepository articles,
			TickerRelevanceTagger tagger, PositionRepository positions, MarketClock clock,
			AgentEventPublisher events, NewsIngestionProperties props) {
		this.sources = sources;
		this.articles = articles;
		this.tagger = tagger;
		this.positions = positions;
		this.clock = clock;
		this.events = events;
		this.props = props;
	}

	/**
	 * Scheduler tick. Fires every {@code argus.news.poll-ms} but only runs a cycle once the cadence
	 * for the current session has elapsed. Never throws out of the scheduler.
	 */
	@Scheduled(fixedDelayString = "${argus.news.poll-ms:60000}",
			initialDelayString = "${argus.news.poll-ms:60000}")
	public void scheduledTick() {
		try {
			Instant now = Instant.now();
			if (!cadenceElapsed(now)) {
				return;
			}
			lastCycle = now;
			ingestOnce();
		} catch (RuntimeException ex) {
			log.warn("News ingestion cycle failed: {}", ex.getMessage());
		}
	}

	private boolean cadenceElapsed(Instant now) {
		if (lastCycle == null) {
			return true;
		}
		long cadenceMs = clock.isRegularHours(now) ? props.regularCadenceMs() : props.offHoursCadenceMs();
		return now.toEpochMilli() - lastCycle.toEpochMilli() >= cadenceMs;
	}

	/**
	 * Run one ingestion pass across all sources. Returns how many new articles were stored. Public so
	 * tests (and a future manual trigger) can drive a deterministic cycle without the scheduler.
	 */
	public int ingestOnce() {
		if (sources.isEmpty()) {
			return 0;
		}
		Set<String> held = heldTickers();
		List<RawArticle> raws = new ArrayList<>();
		for (NewsSource source : sources) {
			try {
				raws.addAll(source.fetch(held));
			} catch (RuntimeException ex) {
				log.warn("News source {} failed: {}", source.name(), ex.getMessage());
			}
		}
		int stored = 0;
		for (RawArticle raw : raws) {
			if (store(raw, held)) {
				stored++;
			}
		}
		log.info("News ingestion: {} fetched, {} new across {} source(s)", raws.size(), stored, sources.size());
		return stored;
	}

	private boolean store(RawArticle raw, Set<String> held) {
		if (raw.externalId() == null || raw.externalId().isBlank()
				|| articles.existsBySourceAndExternalId(raw.source(), raw.externalId())) {
			return false;
		}
		List<String> tickers = tagger.tag(raw, held);
		NewsArticle saved = articles.save(new NewsArticle(raw.source(), raw.externalId(), raw.url(),
				raw.headline(), raw.summary(), raw.publishedAt(), tickers.toArray(String[]::new)));
		events.publish(STREAM_KEY, EVENT_INGESTED, Map.of(
				"articleId", saved.getId(),
				"source", saved.getSource(),
				"headline", saved.getHeadline(),
				"tickers", tickers,
				"publishedAt", saved.getPublishedAt().toString()));
		return true;
	}

	private Set<String> heldTickers() {
		Set<String> held = new LinkedHashSet<>();
		for (Position p : positions.findAllByOrderByTickerAsc()) {
			if (p.getTicker() != null) {
				held.add(p.getTicker().trim().toUpperCase());
			}
		}
		return held;
	}
}
