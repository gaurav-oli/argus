package com.argus.intelligence;

import com.argus.agent.AgentEventPublisher;
import com.argus.intelligence.MarketDataPort.MarketStats;
import com.argus.notification.Notification;
import com.argus.notification.NotificationService;
import com.argus.notification.UrgencyTier;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * The Stranger Danger Protocol (Story 4.4, FR-10). On a schedule it scans recent news for heavy
 * coverage of "stranger" tickers — symbols not in the {@link KnownUniverse} — and for each one over
 * the coverage threshold computes a pump-and-dump risk score, persists a {@link StrangerAlert} with
 * the elevated 6/7 agent-consensus bar Agent 5 must clear, and (on first detection) emits a
 * {@code stranger.detected} signal. Re-detections refresh the row without re-emitting, so Agent 5
 * isn't spammed each cycle.
 */
@Service
public class StrangerDangerService {

	/** Signal stream consumed by Agent 5 (Epic 6) to apply elevated scrutiny to a stranger. */
	public static final String STREAM_KEY = "argus:stream:signal.stranger";
	static final String EVENT_DETECTED = "stranger.detected";

	private static final Logger log = LoggerFactory.getLogger(StrangerDangerService.class);

	private final NewsArticleRepository articles;
	private final CashtagExtractor cashtags;
	private final KnownUniverse knownUniverse;
	private final SourceCredibilityRepository credibility;
	private final StrangerRiskScorer scorer;
	private final StrangerAlertRepository alerts;
	private final ObjectProvider<MarketDataPort> marketData;
	private final AgentEventPublisher events;
	private final StrangerDangerProperties props;
	private final NotificationService notifications;

	public StrangerDangerService(NewsArticleRepository articles, CashtagExtractor cashtags,
			KnownUniverse knownUniverse, SourceCredibilityRepository credibility, StrangerRiskScorer scorer,
			StrangerAlertRepository alerts, ObjectProvider<MarketDataPort> marketData,
			AgentEventPublisher events, StrangerDangerProperties props, NotificationService notifications) {
		this.articles = articles;
		this.cashtags = cashtags;
		this.knownUniverse = knownUniverse;
		this.credibility = credibility;
		this.scorer = scorer;
		this.alerts = alerts;
		this.marketData = marketData;
		this.events = events;
		this.props = props;
		this.notifications = notifications;
	}

	@Scheduled(fixedDelayString = "${argus.news.stranger.poll-ms:60000}",
			initialDelayString = "${argus.news.stranger.poll-ms:60000}")
	public void scheduledScan() {
		try {
			scan();
		} catch (RuntimeException ex) {
			log.warn("Stranger Danger scan failed: {}", ex.getMessage());
		}
	}

	/**
	 * Run one scan over the recent-article window. Returns the number of newly-detected strangers
	 * (those that produced a fresh alert + signal this cycle). Public so tests drive it deterministically.
	 *
	 * <p>Not {@code @Transactional}: the scheduled entry point ({@link #scheduledScan()}) invokes this
	 * on {@code this}, so a method-level transaction would be bypassed by the proxy anyway. Each
	 * stranger's upsert is an independent repository save, and a failure on one stranger is isolated so
	 * it can't abort the rest of the scan.
	 */
	public int scan() {
		Instant windowStart = Instant.now().minus(Duration.ofMinutes(props.windowMinutes()));
		Set<String> known = knownUniverse.knownTickers();
		Map<String, List<NewsArticle>> byStranger = coverageByStranger(windowStart, known);

		int newlyDetected = 0;
		for (Map.Entry<String, List<NewsArticle>> entry : byStranger.entrySet()) {
			List<NewsArticle> covering = entry.getValue();
			if (covering.size() < props.coverageThreshold()) {
				continue;
			}
			try {
				if (assess(entry.getKey(), covering, windowStart)) {
					newlyDetected++;
				}
			} catch (RuntimeException ex) {
				log.warn("Stranger assessment for '{}' failed: {}", entry.getKey(), ex.getMessage());
			}
		}
		if (newlyDetected > 0) {
			log.info("Stranger Danger: {} new stranger(s) flagged", newlyDetected);
		}
		return newlyDetected;
	}

	/** Group window articles by the stranger symbols they mention (cashtags not in the known universe). */
	private Map<String, List<NewsArticle>> coverageByStranger(Instant windowStart, Set<String> known) {
		Map<String, List<NewsArticle>> byStranger = new LinkedHashMap<>();
		for (NewsArticle article : articles.findByPublishedAtAfterOrderByPublishedAtDesc(windowStart)) {
			String text = nullToEmpty(article.getHeadline()) + " " + nullToEmpty(article.getSummary());
			for (String symbol : cashtags.extract(text)) {
				if (!known.contains(symbol)) {
					byStranger.computeIfAbsent(symbol, k -> new ArrayList<>()).add(article);
				}
			}
		}
		return byStranger;
	}

	private boolean assess(String ticker, List<NewsArticle> covering, Instant windowStart) {
		List<String> sources = covering.stream().map(NewsArticle::getSource).distinct().toList();
		double avgSourceScore = sources.stream()
				.mapToInt(s -> credibility.findBySource(s)
						.map(SourceCredibility::getScore).orElse(SourceCredibility.UNKNOWN_START))
				.average().orElse(SourceCredibility.UNKNOWN_START);
		Optional<MarketStats> stats = marketStats(ticker);
		int risk = scorer.score(covering.size(), sources.size(), avgSourceScore, stats);
		StrangerAssessment assessment =
				new StrangerAssessment(covering.size(), sources.size(), avgSourceScore, risk);

		Optional<StrangerAlert> existing = alerts.findByTicker(ticker);
		if (existing.isPresent()) {
			existing.get().apply(assessment);
			alerts.save(existing.get());
			return false; // refresh only — already signalled
		}
		StrangerAlert alert = new StrangerAlert(ticker, assessment, props.requiredAgentConsensus(), windowStart);
		alerts.save(alert);
		events.publish(STREAM_KEY, EVENT_DETECTED, Map.of(
				"ticker", ticker,
				"riskScore", risk,
				"coverageCount", covering.size(),
				"requiredConsensus", props.requiredAgentConsensus()));
		log.warn("Stranger Danger: '{}' flagged (risk {}, {} articles, elevated consensus {}/7)",
				ticker, risk, covering.size(), props.requiredAgentConsensus());
		// CRITICAL alert → notification pipeline (Epic 8). CRITICAL bypasses the fatigue gate but still
		// dedups by ticker so re-flags don't re-ping. Best-effort: a failure must never abort a scan.
		try {
			notifications.notify(Notification.forTicker(UrgencyTier.CRITICAL, ticker, "STRANGER",
					risk / 100.0, 0.0,
					"⚠️ Stranger danger: " + ticker,
					ticker + " is under heavy unverified coverage (risk " + risk + "/100). Treat with caution.",
					"/intelligence"));
		} catch (RuntimeException ex) {
			log.warn("Stranger Danger notification for '{}' failed: {}", ticker, ex.getMessage());
		}
		return true;
	}

	private Optional<MarketStats> marketStats(String ticker) {
		MarketDataPort port = marketData.getIfAvailable();
		return port == null ? Optional.empty() : port.statsFor(ticker);
	}

	private static String nullToEmpty(String s) {
		return s == null ? "" : s;
	}
}
