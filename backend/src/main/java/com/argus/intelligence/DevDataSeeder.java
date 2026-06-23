package com.argus.intelligence;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Seeds a small set of representative Agent-1 outputs on first dev run (Epic 4) so the Intelligence
 * UI has content without a live Finnhub key or a full ingestion cycle. Dev-profile only and gated by
 * {@code argus.dev.seed} (off in tests). Idempotent: it no-ops once any news exists. The source
 * scores are produced by driving the real credibility engine, so the tiers are genuine.
 */
@Component
@Profile("dev")
@ConditionalOnProperty(name = "argus.dev.seed", havingValue = "true")
public class DevDataSeeder {

	private static final Logger log = LoggerFactory.getLogger(DevDataSeeder.class);

	private final NewsArticleRepository articles;
	private final SourceCredibilityService credibility;
	private final StrangerAlertRepository strangers;

	public DevDataSeeder(NewsArticleRepository articles, SourceCredibilityService credibility,
			StrangerAlertRepository strangers) {
		this.articles = articles;
		this.credibility = credibility;
		this.strangers = strangers;
	}

	@EventListener(ApplicationReadyEvent.class)
	public void seed() {
		if (articles.count() > 0) {
			return; // already seeded / real data present
		}
		log.info("Seeding sample Agent-1 intelligence data (argus.dev.seed=true)");

		// Source credibility — drive the real engine so tiers/blocking are genuine (Story 4.3).
		applyOutcomes("Reuters", true, 28);        // → Platinum
		applyOutcomes("Bloomberg", true, 12);      // → Silver
		applyOutcomes("Seeking Alpha", true, 5);   // → Bronze
		applyOutcomes("dailycrypto.biz", false, 10); // → Blocked (+ notification)

		Instant now = Instant.now();
		articles.saveAll(List.of(
				analyzed("Reuters", "seed-1", "Apple unveils record services revenue, raises buyback",
						now.minus(Duration.ofMinutes(12)), new String[] {"AAPL"},
						SentimentLabel.BULLISH, 0.78, 0.91),
				analyzed("Bloomberg", "seed-2", "Microsoft cloud growth slows, shares dip after hours",
						now.minus(Duration.ofMinutes(48)), new String[] {"MSFT"},
						SentimentLabel.BEARISH, -0.42, 0.74),
				analyzed("Reuters", "seed-3", "Nvidia data-center demand stays strong into next quarter",
						now.minus(Duration.ofHours(2)), new String[] {"NVDA"},
						SentimentLabel.BULLISH, 0.66, 0.83),
				analyzed("Seeking Alpha", "seed-4", "Markets mixed as investors weigh rate path",
						now.minus(Duration.ofHours(3)), new String[0],
						SentimentLabel.NEUTRAL, 0.05, 0.30),
				analyzed("dailycrypto.biz", "seed-5", "$ZZZP set to EXPLODE 1000% — do not miss this rocket",
						now.minus(Duration.ofMinutes(20)), new String[0],
						SentimentLabel.BULLISH, 0.95, 0.20)));

		strangers.save(new StrangerAlert("ZZZP",
				new StrangerAssessment(7, 1, 8.0, 82), 6, now.minus(Duration.ofHours(6))));

		log.info("Seeded {} news articles, 4 sources, 1 stranger alert", articles.count());
	}

	private void applyOutcomes(String source, boolean correct, int times) {
		credibility.register(source);
		for (int i = 0; i < times; i++) {
			credibility.recordOutcome(source, correct);
		}
	}

	private NewsArticle analyzed(String source, String externalId, String headline, Instant when,
			String[] tickers, SentimentLabel label, double score, double relevance) {
		NewsArticle a = new NewsArticle(source, externalId, "https://example.com/" + externalId,
				headline, headline + ".", when, tickers);
		a.applySentiment(new SentimentAnalysis(label, score, relevance), when);
		return a;
	}
}
