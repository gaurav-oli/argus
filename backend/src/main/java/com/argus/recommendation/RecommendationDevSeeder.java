package com.argus.recommendation;

import java.math.BigDecimal;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Seeds a couple of sample Agent 5 recommendations on first dev run (Epic 6) so the Probability
 * Forecast Card UI has content without waiting for the live trigger. Dev-profile only, gated by
 * {@code argus.dev.seed} (off in tests), idempotent.
 */
@Component
@Profile("dev")
@ConditionalOnProperty(name = "argus.dev.seed", havingValue = "true")
public class RecommendationDevSeeder {

	private static final Logger log = LoggerFactory.getLogger(RecommendationDevSeeder.class);

	private final RecommendationService recommendations;
	private final RecommendationRepository repository;

	public RecommendationDevSeeder(RecommendationService recommendations, RecommendationRepository repository) {
		this.recommendations = recommendations;
		this.repository = repository;
	}

	@EventListener(ApplicationReadyEvent.class)
	public void seed() {
		if (repository.count() > 0) {
			return;
		}
		recommendations.create("AAPL", List.of(
				new AgentSignal("agent-1-news", SignalDirection.BULLISH, 0.9, "Strong services-revenue coverage"),
				new AgentSignal("agent-7-calendar", SignalDirection.BEARISH, 0.3, "Earnings within 5 days")),
				new BigDecimal("250.00"), "3 months");
		recommendations.create("MSFT", List.of(
				new AgentSignal("agent-1-news", SignalDirection.BEARISH, 0.6, "Cloud-growth slowdown reported")),
				new BigDecimal("400.00"), "3 months");
		log.info("Seeded {} sample recommendations", repository.count());
	}
}
