package com.argus.recommendation;

import com.argus.agent.Agent;
import com.argus.agent.EventEnvelope;
import com.argus.calendar.EarningsQuietPeriodService;
import com.argus.calendar.QuietPeriodStatus;
import com.argus.intelligence.StrangerDangerService;
import com.argus.portfolio.Position;
import com.argus.portfolio.PositionRepository;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Agent 5's hybrid trigger (Story 6.4, FR-14). It wakes two ways: on a high-impact signal — the
 * Stranger Danger stream from Agent 1 (Epic 4) — and on a 6-hourly review of holdings. Either way it
 * gathers the available agent signals, then produces a recommendation through the scoring engine,
 * subject to two gates: a FROZEN graduation state (Story 6.6) blocks all new recommendations, and an
 * earnings quiet period (Story 5.3) suppresses the probability card in favour of an "earnings ahead"
 * posture. Because the agent runtime polls sub-second, a streamed signal is consumed within ~2 min.
 */
@Component
public class RecommendationTrigger implements Agent {

	private static final Logger log = LoggerFactory.getLogger(RecommendationTrigger.class);

	private final AgentSignalGatherer gatherer;
	private final RecommendationService recommendations;
	private final GraduationService graduation;
	private final EarningsQuietPeriodService quietPeriod;
	private final PositionRepository positions;

	public RecommendationTrigger(AgentSignalGatherer gatherer, RecommendationService recommendations,
			GraduationService graduation, EarningsQuietPeriodService quietPeriod, PositionRepository positions) {
		this.gatherer = gatherer;
		this.recommendations = recommendations;
		this.graduation = graduation;
		this.quietPeriod = quietPeriod;
		this.positions = positions;
	}

	@Override
	public String name() {
		return "recommendation-trigger";
	}

	@Override
	public String streamKey() {
		return StrangerDangerService.STREAM_KEY; // wake on Agent 1's high-impact stranger signals
	}

	@Override
	public void handle(EventEnvelope event) {
		Object ticker = event.payload().get("ticker");
		if (ticker != null) {
			trigger(String.valueOf(ticker));
		}
	}

	/** Six-hourly routine review of holdings, feeding the briefing (FR-14). */
	@Scheduled(cron = "0 0 */6 * * *")
	public void scheduledReview() {
		try {
			for (Position p : positions.findAllByOrderByTickerAsc()) {
				if (p.getTicker() != null) {
					trigger(p.getTicker());
				}
			}
		} catch (RuntimeException ex) {
			log.warn("Scheduled recommendation review failed: {}", ex.getMessage());
		}
	}

	/**
	 * Produce a recommendation for {@code ticker} if the gates allow. Returns the recommendation, or
	 * empty when suppressed (FROZEN, quiet period, or no signals). Public so tests drive it directly.
	 */
	public Optional<Recommendation> trigger(String ticker) {
		if (graduation.currentState() == GraduationState.FROZEN) {
			log.debug("Agent 5 FROZEN — suppressing recommendation for {}", ticker);
			return Optional.empty();
		}
		if (quietPeriod.statusFor(ticker).status() == QuietPeriodStatus.Status.QUIET) {
			log.info("Earnings ahead for {} — suppressing probability card (quiet period)", ticker);
			return Optional.empty();
		}
		List<AgentSignal> signals = gatherer.gather(ticker);
		if (signals.isEmpty()) {
			return Optional.empty();
		}
		Recommendation rec = recommendations.create(ticker, signals, null, "6h review");
		log.info("Agent 5 produced recommendation for {} ({} signals)", ticker, signals.size());
		return Optional.of(rec);
	}
}
