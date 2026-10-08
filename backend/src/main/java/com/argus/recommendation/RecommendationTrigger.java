package com.argus.recommendation;

import com.argus.agent.Agent;
import com.argus.agent.EventEnvelope;
import com.argus.calendar.EarningsQuietPeriodService;
import com.argus.calendar.QuietPeriodStatus;
import com.argus.intelligence.KnownUniverse;
import com.argus.intelligence.StrangerDangerService;
import com.argus.deepanalysis.DeepAnalysisService;
import com.argus.deepanalysis.DeepView;
import com.argus.regime.MarketRegime;
import com.argus.regime.MarketRegimeService;
import com.argus.regime.Sector;
import com.argus.regime.SectorClassifier;
import com.argus.technical.ChartStudy;
import com.argus.technical.ChartStudyService;
import com.argus.technical.LivePriceService;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
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
	private final KnownUniverse universe;
	private final PaperInvestorService investor;
	private final RecommendationPolicy policy;
	private final SectorClassifier sectors;
	private final MarketRegimeService regimes;
	private final ChartStudyService charts;
	private final DeepAnalysisService deepAnalyses;
	private final LivePriceService livePrices;

	public RecommendationTrigger(AgentSignalGatherer gatherer, RecommendationService recommendations,
			GraduationService graduation, EarningsQuietPeriodService quietPeriod, KnownUniverse universe,
			PaperInvestorService investor, RecommendationPolicy policy, SectorClassifier sectors,
			MarketRegimeService regimes, LivePriceService livePrices, ChartStudyService charts,
			DeepAnalysisService deepAnalyses) {
		this.livePrices = livePrices;
		this.gatherer = gatherer;
		this.recommendations = recommendations;
		this.graduation = graduation;
		this.quietPeriod = quietPeriod;
		this.universe = universe;
		this.investor = investor;
		this.policy = policy;
		this.sectors = sectors;
		this.regimes = regimes;
		this.charts = charts;
		this.deepAnalyses = deepAnalyses;
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

	/** Set false in tests so no review fires against the shared test database at context start. */
	@Value("${argus.boot-catch-up.enabled:true}")
	private boolean catchUpOnBoot = true;

	/** A review is "missed" once the newest call is older than one six-hourly pass. */
	private static final Duration REVIEW_INTERVAL = Duration.ofHours(6);

	/** Lets live prices and the market regime warm up before the catch-up pass scores anything. */
	private static final Duration CATCH_UP_DELAY = Duration.ofMinutes(2);

	/**
	 * Boot catch-up: a host that was down across a review slot (00/06/12/18 UTC) would otherwise wait
	 * for the next one, leaving the board on old calls and Agent 5 reading as stalled. Shortly after
	 * startup, run one review if the newest call is older than a pass.
	 */
	@EventListener(ApplicationReadyEvent.class)
	public void catchUpOnStartup() {
		if (!catchUpOnBoot) {
			return;
		}
		Thread.startVirtualThread(() -> {
			try {
				Thread.sleep(CATCH_UP_DELAY);
				Optional<Instant> latest = recommendations.latestCreatedAt();
				if (latest.isEmpty() || latest.get().isBefore(Instant.now().minus(REVIEW_INTERVAL))) {
					log.info("Agent 5 startup catch-up: newest call {}", latest.map(Instant::toString).orElse("none"));
					scheduledReview();
				}
			}
			catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
			}
			catch (RuntimeException ex) {
				log.warn("Agent 5 startup catch-up failed: {}", ex.getMessage());
			}
		});
	}

	/** Six-hourly routine review of the known universe (holdings + watchlist), feeding the briefing (FR-14). */
	@Scheduled(cron = "0 0 */6 * * *")
	public void scheduledReview() {
		try {
			// The known universe already dedups tickers (a holding split across accounts is one ticker)
			// and, via CompositeKnownUniverse, now spans holdings + the watchlist.
			universe.knownTickers().forEach(this::trigger);
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
		QuietPeriodStatus quiet = quietPeriod.statusFor(ticker);
		if (quiet.status() == QuietPeriodStatus.Status.QUIET) {
			log.info("Earnings ahead for {} — suppressing probability card (quiet period)", ticker);
			return Optional.empty();
		}
		List<AgentSignal> signals = gatherer.gather(ticker);
		if (signals.isEmpty()) {
			return Optional.empty();
		}
		// Context the raw scoring engine cannot see: what sector this is, what the tape is doing, where
		// the stock trades and how far it has already moved today.
		Sector sector = sectors.sectorOf(ticker);
		MarketRegime regime = regimes.current();
		// Streaming price for holdings, else a polled quote — watchlist names have no stream.
		Double lastPrice = livePrices.livePrice(ticker).orElse(null);
		Double move1d = regimes.moveOf(ticker).map(MarketRegimeService.StockMove::changePct1d).orElse(null);
		boolean earningsSoon = quiet.status() == QuietPeriodStatus.Status.NOTE;
		ChartStudy chart = charts.studyFor(ticker, lastPrice).orElse(null);
		DeepView deep = deepAnalyses.viewFor(ticker).orElse(null);
		RecommendationPolicy.Standing standing = gatherer.standing(ticker);

		Recommendation rec = recommendations.create(ticker, signals,
				score -> policy.evaluate(new RecommendationPolicy.Context(ticker, score, signals, sector, regime,
						lastPrice, move1d, earningsSoon, chart, deep, standing)),
				sector.name(), "6h review");
		log.info("Agent 5 {} {} — conviction {}/100, hold {}d ({} signals)", rec.getTicker(), rec.getAction(),
				rec.getConvictionScore(), rec.getHoldDays(), signals.size());
		// Every call — WATCH included — is first checked against what the book already holds on this ticker:
		// a call the other way exits those legs, a WATCH tightens their stops.
		investor.reviewOpenAgainst(rec);
		if (rec.getAction().actionable()) {
			investor.open(rec); // only a call with a real edge earns a paper position to validate it
		}
		return Optional.of(rec);
	}
}
