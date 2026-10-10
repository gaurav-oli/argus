package com.argus.recommendation;

import com.argus.marketdata.BenchmarkPriceSource;
import com.argus.model.ModelGateway;
import com.argus.portfolio.CorrelationRisk;
import com.argus.deepanalysis.DeepAnalysis;
import com.argus.deepanalysis.DeepAnalysisService;
import com.argus.deepanalysis.DeepVerdict;
import com.argus.learning.FeatureTokens;
import com.argus.learning.LessonEffect;
import com.argus.learning.PatternAdvice;
import com.argus.learning.PatternLibrary;
import com.argus.learning.Lessons;
import com.argus.regime.SectorClassifier;
import com.argus.technical.ChartStudy;
import com.argus.technical.ChartStudyService;
import com.argus.technical.LivePriceService;
import com.argus.recommendation.TradeDecision.Decision;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The Investor persona (FR-11 follow-up). It closes the loop on Agent 5's recommendations without any
 * human input: when the Analyst makes a directional call, the Investor {@link #open opens} one
 * fixed-notional simulated leg per horizon (default 7/30/90 days) — but only when that
 * (ticker, direction, horizon) thesis isn't already open; a repeat recommendation re-affirms the open
 * legs instead of duplicating them (Fable 5 review: pseudo-replication would let correlated duplicates
 * satisfy the learning gates). A scheduled pass {@link #closeDueTrades marks due positions to market},
 * decides win/loss by the direction-adjusted return <em>in excess of SPY</em> (so the loop measures
 * signal, not market beta; absolute return when no benchmark was captured), and feeds the outcome into
 * the existing {@link GraduationService}. On a loss it asks the model for a short post-mortem. Prices
 * come from {@link LivePriceService} — the live feed for held names, a polled quote for every other tracked
 * ticker (before, only held names had a price, so a call on any watchlist/discovered stock silently never
 * opened a trade); SPY via {@link BenchmarkPriceSource}.
 */
@Service
public class PaperInvestorService {

	private static final Logger log = LoggerFactory.getLogger(PaperInvestorService.class);
	private static final List<Integer> DEFAULT_HORIZONS = List.of(7, 30, 90);
	/** Same bar {@link com.argus.portfolio.HealthScoreService} uses to flag a correlated pair in a real portfolio. */
	private static final double CORRELATION_THRESHOLD = 0.7;
	/** Below this many shared trading days, a correlation read is too thin to trust — stay silent, don't block. */
	private static final int MIN_COMMON_TRADING_DAYS = 20;

	private final SimulatedTradeRepository trades;
	private final LivePriceService prices;
	private final BenchmarkPriceSource benchmark;
	private final GraduationService graduation;
	private final TradeConfirmationService confirmations;
	private final ModelGateway gateway;
	private final BigDecimal notional;
	private final List<Integer> horizons;
	/** True when the legacy single-horizon validation knob is forcing one horizon for every call. */
	private final boolean horizonForced;
	private final SectorClassifier sectors;
	private final int maxOpenPerSectorDirection;
	private final int maxOpenPerCorrelatedCluster;
	private final Lessons lessons;
	private final ChartStudyService charts;
	private final DeepAnalysisService deepAnalyses;
	private final RecommendationRepository recommendations;
	private final PatternLibrary patterns;

	public PaperInvestorService(SimulatedTradeRepository trades, LivePriceService prices,
			BenchmarkPriceSource benchmark, GraduationService graduation,
			TradeConfirmationService confirmations, ModelGateway gateway,
			@Value("${argus.paper-investor.notional:100}") BigDecimal notional,
			@Value("${argus.paper-investor.horizon-days-list:}") String horizonList,
			@Value("${argus.paper-investor.horizon-days:0}") int legacySingleHorizon,
			SectorClassifier sectors,
			@Value("${argus.paper-investor.max-open-per-sector-direction:4}") int maxOpenPerSectorDirection,
			@Value("${argus.paper-investor.max-open-per-correlated-cluster:3}") int maxOpenPerCorrelatedCluster,
			Lessons lessons, ChartStudyService charts, DeepAnalysisService deepAnalyses,
			RecommendationRepository recommendations, PatternLibrary patterns) {
		this.trades = trades;
		this.prices = prices;
		this.benchmark = benchmark;
		this.graduation = graduation;
		this.confirmations = confirmations;
		this.gateway = gateway;
		this.notional = notional;
		this.horizons = resolveHorizons(horizonList, legacySingleHorizon);
		this.horizonForced = legacySingleHorizon > 0;
		this.sectors = sectors;
		this.maxOpenPerSectorDirection = maxOpenPerSectorDirection;
		this.maxOpenPerCorrelatedCluster = maxOpenPerCorrelatedCluster;
		this.lessons = lessons;
		this.charts = charts;
		this.deepAnalyses = deepAnalyses;
		this.recommendations = recommendations;
		this.patterns = patterns;
	}

	// ---- entry-time intelligence: lessons, size, and a chart-based protective stop ----

	/**
	 * When the current recommendation system went live (conviction scoring + Agents 11-13). A leg opened
	 * before this by the old coin-flip system no longer blocks a new-system leg on the same thesis: the old
	 * one runs to its own horizon and is scored on its own, so the two eras' results stay comparable. The
	 * concentration caps still count every open leg — the book's real exposure doesn't care which era.
	 */
	static final java.time.Instant NEW_SYSTEM_SINCE = java.time.Instant.parse("2026-09-25T00:00:00Z");

	/** Exit reasons that mean "the market proved the position wrong" — what the cooldown and breaker count. */
	static final List<String> STOP_OUTS = List.of("STOP", "TRAILING_STOP");
	/** After a stop-out, no re-entry on the same ticker and direction for this long. */
	static final java.time.Duration REENTRY_COOLDOWN = java.time.Duration.ofDays(3);
	/** This many stop-outs inside {@link #BREAKER_WINDOW} pause all new entries until the window clears. */
	static final int BREAKER_STOP_OUTS = 3;
	static final java.time.Duration BREAKER_WINDOW = java.time.Duration.ofHours(24);
	/** An early exit's counterfactual is only taken within this long after its horizon, so it reflects that day's price. */
	static final java.time.Duration COUNTERFACTUAL_WINDOW = java.time.Duration.ofDays(3);

	/** Deep verdict must be at least this convincing to flip an open position. */
	private static final int FLIP_MIN_CONVICTION = 60;

	/** Agent 10's chart-derived stop ({@link StopLoss}) — the actual stop on this simulated position. */
	BigDecimal stopFor(SignalDirection direction, String ticker, BigDecimal entry) {
		return StopLoss.stopFor(direction, chartAt(ticker, entry), entry.doubleValue());
	}

	/** Agent 10's chart with its levels measured from {@code price} (today's) — null when unavailable. */
	ChartStudy chartAt(String ticker, BigDecimal price) {
		try {
			// Levels measured from the entry (today's price): a stop from yesterday's levels could sit on the
			// wrong side of a stock that already broke through them today.
			return charts.studyFor(ticker, price.doubleValue()).orElse(null);
		}
		catch (RuntimeException ex) {
			log.debug("Investor: chart unavailable for {}: {}", ticker, ex.getMessage());
			return null;
		}
	}

	/**
	 * Where half the position comes off: the same sell target the recommendation card shows (Agent 10's nearest
	 * resistance for a long, support for a short). Null for a core hold (no fixed target — trail only), or when the
	 * target isn't at least 1% beyond entry.
	 */
	BigDecimal targetFor(Recommendation rec, BigDecimal entry, ChartStudy chart) {
		if (rec.getAction() == null) {
			return null;
		}
		try {
			String valuation = com.argus.learning.FeatureTokens.fromJson(rec.getFeatures()).stream()
					.filter(t -> t.startsWith("val=")).map(t -> t.substring(4)).findFirst().orElse(null);
			PriceGuidance.Guidance g = PriceGuidance.build(rec.getAction(), rec.getHoldDays() == null ? 0 : rec.getHoldDays(),
					entry.doubleValue(), chart, deepAnalyses.viewFor(rec.getTicker()).orElse(null), valuation);
			BigDecimal sell = g == null ? null : g.sellPrice();
			if (sell == null) {
				return null;
			}
			boolean bullish = rec.getDirection() == SignalDirection.BULLISH;
			double gap = sell.doubleValue() / entry.doubleValue() - 1;
			return (bullish ? gap >= 0.01 : gap <= -0.01) ? sell : null;
		}
		catch (RuntimeException ex) {
			log.debug("Investor: no target for {}: {}", rec.getTicker(), ex.getMessage());
			return null;
		}
	}

	/** On an adverse event, the stop moves to keep at least this share of an open trade's current profit. */
	static final double PROFIT_LOCK_SHARE = 0.5;

	/**
	 * A clearly adverse event (bad news for a long, good news for a short — see {@code ChangeWatcher}) hit {@code ticker}:
	 * every open trade on it that is in profit has its stop moved to keep at least {@value #PROFIT_LOCK_SHARE} of that
	 * profit, whatever Agent 5 then concludes. A trade can still be up +3% and inside its normal daily noise, so the
	 * trailing rules haven't locked anything yet; without this, a "still BUY" re-review let bad news turn it into a loss.
	 * Protective, so it ignores the 24h minimum hold. Returns how many stops moved.
	 */
	public int lockProfitOnAdverseEvent(String ticker, int eventPolarity, String why) {
		if (eventPolarity == 0) {
			return 0;
		}
		int moved = 0;
		for (SimulatedTrade t : trades.findByTickerAndStatus(ticker, SimulatedTrade.Status.OPEN)) {
			boolean bullish = t.getDirection() == SignalDirection.BULLISH;
			if (bullish == (eventPolarity > 0)) {
				continue; // the event favours this position
			}
			BigDecimal live = prices.latestPrice(ticker).orElse(null);
			if (live == null || live.signum() <= 0) {
				continue;
			}
			double price = live.doubleValue();
			double entry = t.getEntryPrice().doubleValue();
			double gain = bullish ? price - entry : entry - price;
			if (gain <= 0) {
				continue; // nothing to protect — the regular stop already governs a losing trade
			}
			double lock = bullish ? entry + PROFIT_LOCK_SHARE * gain : entry - PROFIT_LOCK_SHARE * gain;
			ChartStudy chart = chartAt(ticker, live);
			double atr = (chart == null || chart.atrPct() == null || chart.atrPct() <= 0 ? PositionRules.DEFAULT_ATR_PCT : chart.atrPct()) / 100;
			Double current = t.getStopPrice() == null ? null : t.getStopPrice().doubleValue();
			Double next = PositionRules.tighten(bullish, current, lock, price, atr);
			if (next == null || (current != null && money(next).compareTo(t.getStopPrice()) == 0)) {
				continue;
			}
			t.moveStop(money(next));
			trades.save(t);
			moved++;
			log.info("Investor: {} — locked part of the {} {} profit: stop {} → {} (entry {}, price {})", why, t.getDirection(),
					ticker, current, t.getStopPrice(), t.getEntryPrice(), live);
		}
		return moved;
	}

	/** Scale-in rules: the position must be this many ATRs in profit, and conviction must have risen by this much. */
	static final double SCALE_IN_PROFIT_ATRS = 1.0;
	static final int SCALE_IN_CONVICTION_RISE = 10;
	static final int SCALE_IN_MIN_CONVICTION = 70;

	/**
	 * Add to a winner, like a disciplined trader: once per thesis, half size, only when the position is already at
	 * least {@value #SCALE_IN_PROFIT_ATRS} ATR in profit (never averaging down) and Agent 5's conviction has risen by
	 * {@value #SCALE_IN_CONVICTION_RISE}+ to at least {@value #SCALE_IN_MIN_CONVICTION}. The add-on shares the
	 * position's current stop and target, so it is protected from the moment it opens. Returns the new leg or null.
	 */
	SimulatedTrade scaleIn(Recommendation rec, List<SimulatedTrade> existing, BigDecimal price, ChartStudy chart,
			BigDecimal baseNotional, BigDecimal spy) {
		if (existing.isEmpty() || existing.stream().anyMatch(SimulatedTrade::isScaleIn) || rec.getConvictionScore() == null
				|| rec.getConvictionScore() < SCALE_IN_MIN_CONVICTION) {
			return null;
		}
		SimulatedTrade first = existing.stream().min(java.util.Comparator.comparing(SimulatedTrade::getEntryAt)).orElseThrow();
		Integer before = first.getRecommendationId() == null ? null
				: recommendations.findById(first.getRecommendationId()).map(Recommendation::getConvictionScore).orElse(null);
		if (before == null || rec.getConvictionScore() < before + SCALE_IN_CONVICTION_RISE) {
			return null;
		}
		double atr = (chart == null || chart.atrPct() == null || chart.atrPct() <= 0 ? PositionRules.DEFAULT_ATR_PCT : chart.atrPct()) / 100;
		boolean bullish = rec.getDirection() == SignalDirection.BULLISH;
		double gain = bullish ? price.doubleValue() / first.getEntryPrice().doubleValue() - 1
				: 1 - price.doubleValue() / first.getEntryPrice().doubleValue();
		if (gain < SCALE_IN_PROFIT_ATRS * atr) {
			return null; // only add to a winner — never average down
		}
		SimulatedTrade add = new SimulatedTrade(rec.getId(), rec.getTicker(), rec.getDirection(),
				baseNotional.divide(BigDecimal.valueOf(2), 2, java.math.RoundingMode.HALF_UP), price, first.getHorizonDays(), spy);
		add.applyRisk(first.getStopPrice() != null ? first.getStopPrice() : StopLoss.stopFor(rec.getDirection(), chart, price.doubleValue()), 1.0);
		add.setHighWater(price);
		add.setTargetPrice(first.getTargetPrice());
		add.markScaleIn();
		SimulatedTrade saved = trades.save(add);
		log.info("Investor scaled in to {} {}: conviction {} → {}, position +{}% — added ${} at {} (stop {})", rec.getDirection(),
				rec.getTicker(), before, rec.getConvictionScore(), String.format(java.util.Locale.ROOT, "%.1f", gain * 100),
				add.getNotional(), price, add.getStopPrice());
		return saved;
	}

	/** The target for an already-open current-system leg, from the recommendation that opened it; null otherwise. */
	BigDecimal targetFor(SimulatedTrade trade) {
		if (trade.getRecommendationId() == null || trade.getEntryAt().isBefore(NEW_SYSTEM_SINCE)) {
			return null;
		}
		return recommendations.findById(trade.getRecommendationId())
				.map(rec -> targetFor(rec, trade.getEntryPrice(), chartAt(trade.getTicker(), trade.getEntryPrice())))
				.orElse(null);
	}

	// ---- active management (driven by PositionManager and each new Agent 5 call) ----

	/** Close an open position ahead of its horizon, for {@code reason} (a stop, the target's half, thesis decay). */
	public void closeEarly(SimulatedTrade trade, BigDecimal exit, String reason) {
		closeOne(trade, exit, benchmark.latest().orElse(null), reason);
	}

	/** Take half off at the target: split the leg, close the half as TAKE_PROFIT, keep the rest with a tighter stop. */
	public synchronized void takeHalf(SimulatedTrade trade, BigDecimal exit, BigDecimal restStop) {
		if (trades.findById(trade.getId()).map(t -> t.getStatus() != SimulatedTrade.Status.OPEN || t.isScaledOut()).orElse(true)) {
			return; // closed or already scaled out by a concurrent pass
		}
		SimulatedTrade half = trade.splitHalf();
		if (restStop != null) {
			trade.moveStop(restStop);
		}
		trades.save(trade);
		closeOne(trades.save(half), exit, benchmark.latest().orElse(null), "TAKE_PROFIT");
		log.info("Investor took half off {} {} at the {} target — the rest trails from {}", trade.getDirection(),
				trade.getTicker(), exit, trade.getStopPrice());
	}

	/**
	 * A new Agent 5 call on a ticker the book holds: a call the other way exits those legs (THESIS_DECAY), a WATCH
	 * tightens their stops, an agreeing call leaves them alone. Runs before {@link #open} on every call.
	 */
	public void reviewOpenAgainst(Recommendation rec) {
		Instant now = Instant.now();
		for (SimulatedTrade trade : trades.findByTickerAndStatus(rec.getTicker(), SimulatedTrade.Status.OPEN)) {
			try {
				BigDecimal price = prices.latestPrice(trade.getTicker()).orElse(null);
				if (price == null || price.signum() <= 0) {
					continue;
				}
				boolean actionable = rec.isActionable() && rec.getAction() != null;
				boolean opposite = actionable && rec.getDirection() != trade.getDirection();
				Double atr = java.util.Optional.ofNullable(chartAt(trade.getTicker(), price)).map(ChartStudy::atrPct).orElse(null);
				PositionRules.Decision d = PositionRules.onNewCall(PositionRules.of(trade), opposite, !actionable,
						price.doubleValue(), atr, now);
				if (d.action() == PositionRules.Action.EXIT) {
					log.info("Investor: Agent 5 now calls {} {} — exiting the {} leg (thesis decay)", rec.getAction().label(),
							rec.getTicker(), trade.getDirection());
					closeEarly(trade, price, d.exitReason());
				}
				else if (d.stop() != null && !d.stop().equals(trade.getStopPrice() == null ? null : trade.getStopPrice().doubleValue())) {
					trade.moveStop(money(d.stop()));
					trades.save(trade);
					log.info("Investor: Agent 5 now only watching {} — tightened the {} stop to {}", rec.getTicker(),
							trade.getDirection(), trade.getStopPrice());
				}
			}
			catch (RuntimeException ex) {
				log.warn("Investor: reviewing open {} against the new call failed: {}", trade.getTicker(), ex.getMessage());
			}
		}
	}

	static BigDecimal money(double v) {
		return BigDecimal.valueOf(v).setScale(6, java.math.RoundingMode.HALF_UP);
	}

	/**
	 * For each early exit whose original horizon has just passed, record what holding would have returned — the
	 * evidence for whether active management beats buy-and-wait. Only within a few days of the horizon, so the
	 * counterfactual price is that day's, not a much later one.
	 */
	void recordHoldCounterfactuals(Instant now) {
		for (SimulatedTrade t : trades.findByStatusAndHoldReturnPctIsNullAndExitReasonNot(SimulatedTrade.Status.CLOSED, "HORIZON")) {
			if (now.isBefore(t.horizonAt()) || now.isAfter(t.horizonAt().plus(COUNTERFACTUAL_WINDOW))) {
				continue;
			}
			prices.latestPrice(t.getTicker()).filter(p -> p.signum() > 0).ifPresent(p -> {
				t.recordHoldCounterfactual(p);
				trades.save(t);
			});
		}
	}

	/** Agent 11 has, since this trade opened, reached a confident verdict that opposes the position. */
	private boolean thesisFlipped(SimulatedTrade trade) {
		try {
			boolean bullish = trade.getDirection() == SignalDirection.BULLISH;
			// A verdict that agrees with the position but that the thesis tracker has since flagged AT_RISK is a reason to get out too.
			if (deepAnalyses.latestDone(trade.getTicker())
					.filter(DeepAnalysis::isAtRisk)
					.map(DeepAnalysis::getVerdict)
					.map(v -> bullish ? v == DeepVerdict.WORTH_BUYING : v == DeepVerdict.NOT_WORTH_BUYING)
					.orElse(false)) {
				return true;
			}
			return deepAnalyses.latestDone(trade.getTicker())
					.filter(d -> d.getFinishedAt() != null && d.getFinishedAt().isAfter(trade.getEntryAt()))
					.filter(d -> d.getConviction() != null && d.getConviction() >= FLIP_MIN_CONVICTION)
					.map(DeepAnalysis::getVerdict)
					.map(v -> trade.getDirection() == SignalDirection.BULLISH ? v == DeepVerdict.NOT_WORTH_BUYING : v == DeepVerdict.WORTH_BUYING)
					.orElse(false);
		}
		catch (RuntimeException ex) {
			return false;
		}
	}

	/**
	 * The legs to open for a call: the recommended holding period when the recommendation carries one
	 * (so the feedback loop tests the horizon call itself, not a fixed 7/30/90 fan-out), otherwise the
	 * configured list; the legacy validation knob overrides both.
	 */
	private List<Integer> horizonsFor(Recommendation rec) {
		if (!horizonForced && rec.getHoldDays() != null && rec.getHoldDays() > 0) {
			return List.of(rec.getHoldDays());
		}
		return horizons;
	}

	/** Whether the book already holds as many same-direction positions in this sector as we allow —
	 * about 120 near-identical bullish calls a day was one market bet counted 120 times. */
	private boolean sectorFull(Recommendation rec) {
		if (maxOpenPerSectorDirection <= 0) {
			return false;
		}
		var sector = sectors.sectorOf(rec.getTicker());
		long open = trades.findByStatus(SimulatedTrade.Status.OPEN).stream()
				.filter(t -> t.getDirection() == rec.getDirection() && !t.getTicker().equals(rec.getTicker())
						&& sectors.sectorOf(t.getTicker()) == sector)
				.map(SimulatedTrade::getTicker).distinct().count();
		return open >= maxOpenPerSectorDirection;
	}

	/**
	 * Whether the book already holds as many same-direction positions that actually <em>move together</em>
	 * with this one as we allow — {@link #sectorFull} catches five "different" semis names; this catches
	 * the cross-sector blind spot (NVDA and TSLA are a different sector each, but a shock that hits one
	 * tends to hit both). Uses the same deterministic Pearson-correlation read {@link
	 * com.argus.portfolio.HealthScoreService} uses on a real portfolio, just pointed at the paper book
	 * instead. Silent (never blocks) whenever there isn't enough shared price history to trust a read yet
	 * — same discipline as everywhere else correlation is measured here.
	 */
	private boolean correlatedClusterFull(Recommendation rec) {
		if (maxOpenPerCorrelatedCluster <= 0) {
			return false;
		}
		List<CorrelationRisk.Bar> candidate = bars(rec.getTicker());
		if (candidate.isEmpty()) {
			return false;
		}
		long correlated = trades.findByStatus(SimulatedTrade.Status.OPEN).stream()
				.filter(t -> t.getDirection() == rec.getDirection() && !t.getTicker().equals(rec.getTicker()))
				.map(SimulatedTrade::getTicker).distinct()
				.filter(ticker -> CorrelationRisk.correlation(candidate, bars(ticker), MIN_COMMON_TRADING_DAYS)
						.map(c -> c >= CORRELATION_THRESHOLD).orElse(false))
				.count();
		return correlated >= maxOpenPerCorrelatedCluster;
	}

	private List<CorrelationRisk.Bar> bars(String ticker) {
		try {
			return charts.history(ticker).stream().map(c -> new CorrelationRisk.Bar(c.getCandleDate(), c.getClose().doubleValue())).toList();
		}
		catch (RuntimeException ex) {
			log.debug("Investor: candle history unavailable for {} correlation check: {}", ticker, ex.getMessage());
			return List.of();
		}
	}

	/** Staggered horizons from the list prop; the legacy single-horizon knob (validation) wins when set. */
	private static List<Integer> resolveHorizons(String list, int legacySingle) {
		if (legacySingle > 0) {
			return List.of(legacySingle);
		}
		if (list != null && !list.isBlank()) {
			List<Integer> parsed = java.util.Arrays.stream(list.split(","))
					.map(String::trim).filter(s -> !s.isEmpty())
					.map(Integer::parseInt).filter(h -> h > 0)
					.distinct().sorted().toList();
			if (!parsed.isEmpty()) {
				return parsed;
			}
		}
		return DEFAULT_HORIZONS;
	}

	/**
	 * Open one simulated leg per horizon for a fresh recommendation — skipping any horizon whose
	 * (ticker, direction, horizon) thesis is already open. When every horizon is already open the
	 * recommendation instead re-affirms the open legs (the restatement is itself signal, and counting
	 * it avoids the duplicate-trade pseudo-replication the learning loop would mistake for evidence).
	 * The {@code NEUTRAL} check is defensive only — {@link Recommendation}'s direction is always a
	 * binary bull/bear call ({@code Recommendation(String, ProbabilityScore, ...)}: {@code bull ≥ 0.5 ?
	 * BULLISH : BEARISH}), so this never actually fires for real data; kept in case that ever changes.
	 * No-op for an unpriced ticker either. Best-effort — never breaks the trigger.
	 *
	 * <p>This is also where the Investor's Taken decision gets recorded (Trade Journal, regret analysis
	 * — {@link TradeConfirmationService#recordAgentDecision}): successfully opening or re-affirming a
	 * position is the Investor taking the call. There is currently no agent-driven Declined path — the
	 * Investor acts on every priced, non-duplicate recommendation it gets, so Declined stays reserved
	 * for an actual human pass on a card. An unpriced ticker or the recommendation-id dedup guard are
	 * infra/idempotency skips, not decisions, so neither records anything.
	 */
	@Transactional
	public List<SimulatedTrade> open(Recommendation rec) {
		try {
			if (rec == null || rec.getDirection() == SignalDirection.NEUTRAL || rec.getId() == null) {
				return List.of();
			}
			if (trades.existsByRecommendationId(rec.getId())) {
				return List.of();
			}
			BigDecimal entry = prices.latestPrice(rec.getTicker()).orElse(null);
			if (entry == null || entry.signum() <= 0) {
				log.info("Investor: no live price for {} — not opening a paper trade", rec.getTicker());
				return List.of();
			}
			BigDecimal spy = benchmark.latest().orElse(null);
			// What has this kind of situation cost or earned before? The same lessons that shaped the call
			// now shape how much is put on it (and can stop it outright).
			LessonEffect fx = lessons.evaluate(FeatureTokens.fromJson(rec.getFeatures()));
			if (fx.blockReason() != null) {
				log.info("Investor: lesson blocks {} {} — {}", rec.getDirection(), rec.getTicker(), fx.blockReason());
				return List.of();
			}
			Instant now = Instant.now();
			long recentStopOuts = trades.countByExitReasonInAndClosedAtAfter(STOP_OUTS, now.minus(BREAKER_WINDOW));
			if (recentStopOuts >= BREAKER_STOP_OUTS) {
				log.info("Investor: circuit breaker — {} stop-outs in the last 24h; not opening {} {}", recentStopOuts,
						rec.getDirection(), rec.getTicker());
				return List.of();
			}
			if (trades.existsByTickerAndDirectionAndExitReasonInAndClosedAtAfter(rec.getTicker(), rec.getDirection(), STOP_OUTS,
					now.minus(REENTRY_COOLDOWN))) {
				log.info("Investor: {} {} was stopped out in the last {} days — cooling down, not re-entering",
						rec.getDirection(), rec.getTicker(), REENTRY_COOLDOWN.toDays());
				return List.of();
			}
			if (sectorFull(rec)) {
				log.info("Investor: {} book already holds {} {} names — not stacking {}",
						sectors.sectorOf(rec.getTicker()).label(), maxOpenPerSectorDirection, rec.getDirection(),
						rec.getTicker());
				return List.of();
			}
			if (correlatedClusterFull(rec)) {
				log.info("Investor: book already holds {} {} names correlated with {} — not stacking a concentrated bet",
						maxOpenPerCorrelatedCluster, rec.getDirection(), rec.getTicker());
				return List.of();
			}
			// S-B4: how did similar past setups do? The library can skip the entry, size it down, or tighten
			// its stop; with too few matches (or any failure) it says "no prior pattern" and the trade proceeds.
			java.util.Set<String> fingerprint = FeatureTokens.fromJson(rec.getFeatures());
			PatternAdvice pattern = consultPatterns(rec, fingerprint);
			if (pattern.skip()) {
				log.info("Investor: pattern library skips {} {} — {}", rec.getDirection(), rec.getTicker(), pattern.note());
				return List.of();
			}
			double sizeMultiplier = fx.sizeMultiplier() * pattern.sizeMultiplier();
			BigDecimal tradeNotional = notional.multiply(BigDecimal.valueOf(sizeMultiplier)).setScale(2, java.math.RoundingMode.HALF_UP);
			ChartStudy entryChart = chartAt(rec.getTicker(), entry);
			BigDecimal stop = tightened(StopLoss.stopFor(rec.getDirection(), entryChart, entry.doubleValue()), entry, pattern.stopKeep());
			BigDecimal target = targetFor(rec, entry, entryChart);
			String fingerprintJson = fingerprint.isEmpty() ? null : FeatureTokens.toJson(fingerprint);

			List<SimulatedTrade> opened = new java.util.ArrayList<>();
			for (int horizon : horizonsFor(rec)) {
				if (trades.existsByTickerAndDirectionAndHorizonDaysAndStatusAndEntryAtGreaterThanEqual(
						rec.getTicker(), rec.getDirection(), horizon, SimulatedTrade.Status.OPEN, NEW_SYSTEM_SINCE)) {
					continue; // this leg of the thesis is already on the book
				}
				SimulatedTrade leg = new SimulatedTrade(rec.getId(), rec.getTicker(), rec.getDirection(),
						tradeNotional, entry, horizon, spy);
				leg.applyRisk(stop, sizeMultiplier);
				leg.recordSetup(fingerprintJson, pattern.note());
				leg.setHighWater(entry);
				leg.setTargetPrice(target);
				opened.add(trades.save(leg));
			}
			if (opened.isEmpty()) {
				List<SimulatedTrade> existing = trades.findByTickerAndDirectionAndStatusAndEntryAtGreaterThanEqual(
						rec.getTicker(), rec.getDirection(), SimulatedTrade.Status.OPEN, NEW_SYSTEM_SINCE);
				SimulatedTrade added = scaleIn(rec, existing, entry, entryChart, tradeNotional, spy);
				if (added != null) {
					confirmations.recordAgentDecision(rec.getId(), Decision.TAKEN);
					return List.of(added);
				}
				existing.forEach(SimulatedTrade::reaffirm);
				trades.saveAll(existing);
				log.info("Investor: {} {} thesis already open ({} legs) — re-affirmed",
						rec.getDirection(), rec.getTicker(), existing.size());
			}
			else {
				if (!"NO_PATTERN".equals(pattern.action())) {
					log.info("Investor: {} {} — {}", rec.getDirection(), rec.getTicker(), pattern.note());
				}
				log.info("Investor opened {} × ${} {} leg(s) on {} @ {} (stop {}, horizons {}, SPY {})",
						opened.size(), tradeNotional, rec.getDirection(), rec.getTicker(), entry, stop,
						opened.stream().map(t -> String.valueOf(t.getHorizonDays()))
								.reduce((a, b) -> a + "/" + b).orElse("-"),
						spy == null ? "n/a" : spy);
			}
			confirmations.recordAgentDecision(rec.getId(), Decision.TAKEN);
			return opened;
		} catch (RuntimeException ex) {
			log.warn("Investor: failed to open paper trade for {}: {}",
					rec == null ? "?" : rec.getTicker(), ex.getMessage());
			return List.of();
		}
	}

	/** The pattern library, failing open: any error means "no prior pattern" and the entry proceeds unchanged. */
	private PatternAdvice consultPatterns(Recommendation rec, java.util.Set<String> fingerprint) {
		try {
			PatternAdvice advice = patterns.consult(rec.getId(), rec.getTicker(), rec.getDirection().name(), fingerprint);
			return advice == null ? PatternAdvice.noPattern(0, "No prior pattern.") : advice;
		}
		catch (RuntimeException ex) {
			log.warn("Investor: pattern library failed for {} — proceeding without it: {}", rec.getTicker(), ex.getMessage());
			return PatternAdvice.noPattern(0, "No prior pattern — the library failed; proceeding as planned.");
		}
	}

	/** Move the stop toward the entry so only {@code keep} of its distance remains (1.0 = unchanged). */
	static BigDecimal tightened(BigDecimal stop, BigDecimal entry, double keep) {
		if (keep >= 1.0 || stop == null) return stop;
		BigDecimal distance = stop.subtract(entry).multiply(BigDecimal.valueOf(keep));
		return entry.add(distance).setScale(6, java.math.RoundingMode.HALF_UP);
	}

	/**
	 * Hourly: mark every due position to market. Each close records win/loss (benchmark-relative when
	 * a SPY bracket exists) into the graduation machinery and, on a loss, captures the Analyst's
	 * post-mortem. A ticker with no live price yet is left open and retried next pass.
	 */
	@Scheduled(cron = "${argus.paper-investor.close-cron:0 0 * * * *}")
	public void closeDueTrades() {
		Instant now = Instant.now();
		BigDecimal spy = benchmark.latest().orElse(null); // one benchmark quote per pass
		for (SimulatedTrade trade : trades.findByStatus(SimulatedTrade.Status.OPEN)) {
			BigDecimal exit = prices.latestPrice(trade.getTicker()).orElse(null);
			if (exit == null || exit.signum() <= 0) {
				log.debug("Investor: {} unpriced — retrying next pass", trade.getTicker());
				continue;
			}
			// Three ways a position ends: it ran its horizon, the chart-based stop broke, or Agent 11
			// re-analysed the stock and now argues the opposite with real conviction.
			String reason = trade.isDue(now) ? "HORIZON"
					: trade.isStopHit(exit) ? (trade.isStopTrailed() ? "TRAILING_STOP" : "STOP")
					: thesisFlipped(trade) ? "THESIS_FLIP" : null;
			if (reason == null) {
				continue;
			}
			try {
				closeOne(trade, exit, spy, reason);
			} catch (RuntimeException ex) {
				log.warn("Investor: failed to close paper trade {} ({}): {}",
						trade.getId(), trade.getTicker(), ex.getMessage());
			}
		}
		try {
			recordHoldCounterfactuals(now);
		} catch (RuntimeException ex) {
			log.warn("Investor: hold-to-horizon counterfactuals failed: {}", ex.getMessage());
		}
	}

	/**
	 * Close a single trade, feed the outcome to graduation, and reflect on losses. The trade row (with
	 * its win/loss) and {@link GraduationService#recordOutcome} each persist in their own transaction;
	 * the row is saved first so the scoreboard stays correct even if the graduation feed hiccups.
	 */
	private synchronized void closeOne(SimulatedTrade trade, BigDecimal exit, BigDecimal benchmarkExit, String reason) {
		// The hourly pass and the 5-minute PositionManager can reach the same trade: whoever is second must not
		// close (and score) it again.
		if (trade.getId() != null && trades.findById(trade.getId()).map(t -> t.getStatus() != SimulatedTrade.Status.OPEN).orElse(false)) {
			return;
		}
		trade.close(exit, benchmarkExit, reason);
		boolean won = Boolean.TRUE.equals(trade.getWon());
		if (!won) {
			trade.recordReview(postMortem(trade));
		}
		trades.save(trade);
		// Only a trade the current system opened can move Agent 5's graduation; an old-system leg is just recorded.
		graduation.recordOutcome(won, trade.getRecommendationId(), !trade.getEntryAt().isBefore(NEW_SYSTEM_SINCE));
		// Mirror the realized outcome onto the user's Taken/Declined decision (regret analysis).
		try {
			confirmations.recordOutcomeFromPaperTrade(trade.getRecommendationId(), won);
		} catch (RuntimeException ex) {
			log.debug("Investor: decision-outcome mirror failed for rec {}: {}",
					trade.getRecommendationId(), ex.getMessage());
		}
		log.info("Investor closed {} paper trade on {} ({}d, {}): {}% abs, {} vs SPY ({}) @ {}",
				trade.getDirection(), trade.getTicker(), trade.getHorizonDays(), reason, trade.getReturnPct(),
				trade.getExcessReturnPct() == null ? "unbenchmarked" : trade.getExcessReturnPct() + "%",
				won ? "WON" : "LOST", exit);
	}

	/**
	 * Ask the model why a losing call went wrong — grounded in what Argus actually believed at entry (thesis,
	 * evidence, risks it flagged, the situation tokens, the lessons that applied) and how the trade ended. The
	 * old prompt gave the model only prices, so it invented causes ("the Fed's hawkish pivot", "volume spikes")
	 * that were never in the data and that nothing ever acted on. The model may now cite only what it is given,
	 * and is told to say so when the facts do not explain the loss.
	 */
	private String postMortem(SimulatedTrade t) {
		try {
			String vsMarket = t.getExcessReturnPct() == null ? ""
					: " (%s%% vs the S&P 500 over the same window — the call %s the market)"
							.formatted(t.getExcessReturnPct(),
									t.getExcessReturnPct().signum() > 0 ? "beat" : "lagged");
			Recommendation rec = t.getRecommendationId() == null ? null
					: recommendations.findById(t.getRecommendationId()).orElse(null);
			String belief = rec == null ? "(the original recommendation is no longer available)" : """
					Thesis at entry: %s
					Evidence it rested on: %s
					Risks it flagged: %s
					Situation tokens: %s
					Learned lessons that applied: %s""".formatted(nz(rec.getThesis()), nz(rec.getReasons()), nz(rec.getCaveats()),
					nz(rec.getFeatures()), nz(rec.getLessons()));
			String prompt = """
					You are Argus, an investing analyst reviewing your own recommendation that lost money in a \
					paper trade. Be honest and specific, 1-2 sentences, no disclaimers.

					Use ONLY the facts below. Do not cite news, macro events, indicators or numbers that are not \
					listed here. If the facts do not explain the loss, say the loss looks like ordinary market \
					noise rather than inventing a cause.

					Call: %s on %s, held %d days, ended by %s.
					Entry price %s, exit price %s (direction-adjusted return %s%%%s). Protective stop was %s.
					%s

					In 1-2 sentences: which part of the original reasoning failed (or that none clearly did), \
					and what one concrete thing to weigh differently next time.
					Respond with ONLY the reflection text.
					""".formatted(t.getDirection(), t.getTicker(), t.getHorizonDays(), t.getExitReason(), t.getEntryPrice(),
					t.getExitPrice(), t.getReturnPct(), vsMarket, t.getStopPrice() == null ? "not set" : t.getStopPrice(), belief);
			String out = gateway.generate(prompt);
			if (out != null && !out.isBlank()) {
				String clean = out.replace("```", "").strip();
				return clean.length() <= 400 ? clean : clean.substring(0, 400).strip();
			}
		} catch (RuntimeException ex) {
			log.warn("Investor: post-mortem model call failed for {}: {}", t.getTicker(), ex.getMessage());
		}
		return null;
	}

	private static String nz(String s) {
		return s == null || s.isBlank() ? "(none recorded)" : s;
	}

	// ---- Read side for the scoreboard ----

	/** The Investor's track record: the $ book, its return, win rate, and recent activity. */
	@Transactional(readOnly = true)
	public Scoreboard scoreboard() {
		List<SimulatedTrade> closed = trades.findTop100ByOrderByIdDesc().stream()
				.filter(t -> t.getStatus() == SimulatedTrade.Status.CLOSED)
				.toList();

		int wins = (int) closed.stream().filter(t -> Boolean.TRUE.equals(t.getWon())).count();
		Integer winRatePct = closed.isEmpty() ? null : (int) Math.round(100.0 * wins / closed.size());

		// Realized P&L across the closed book, as a % of the notional deployed.
		BigDecimal deployed = BigDecimal.ZERO;
		BigDecimal pnl = BigDecimal.ZERO;
		for (SimulatedTrade t : closed) {
			deployed = deployed.add(t.getNotional());
			if (t.getReturnPct() != null) {
				pnl = pnl.add(t.getNotional().multiply(t.getReturnPct())
						.divide(BigDecimal.valueOf(100), 2, java.math.RoundingMode.HALF_UP));
			}
		}
		BigDecimal bookReturnPct = deployed.signum() == 0 ? null
				: pnl.multiply(BigDecimal.valueOf(100)).divide(deployed, 2, java.math.RoundingMode.HALF_UP);

		List<ClosedTradeView> recentClosed = closed.stream().limit(15).map(ClosedTradeView::from).toList();
		OpenBook openBook = openBook();
		return new Scoreboard(openBook.count(), closed.size(), wins, winRatePct, notional, deployed, pnl,
				bookReturnPct, openBook.deployed(), openBook.unrealizedPct(), openBook.byTicker(), recentClosed, management());
	}

	/**
	 * One paper trade, buy to sell: when and at what price it opened, how many shares for how much, when, at what price
	 * and why it closed, how long it was held, and what it made. Open trades carry the live price and unrealized result.
	 * {@code system} is CURRENT for trades opened on/after {@link #NEW_SYSTEM_SINCE}, else OLD.
	 */
	public record LedgerRow(long id, String ticker, String direction, String system, String status, Instant openedAt,
			BigDecimal entryPrice, BigDecimal shares, BigDecimal amount, BigDecimal stopPrice, BigDecimal targetPrice,
			Instant closedAt, BigDecimal exitPrice, String exitReason, Long heldDays, BigDecimal returnPct, BigDecimal pnl,
			BigDecimal vsSpyPct, Boolean won, boolean scaleIn, boolean takeProfitHalf, BigDecimal currentPrice,
			BigDecimal unrealizedPct, String review, String patternAdvice) {
	}

	/** Every paper trade, newest first — the Investor's full trade journal. */
	@Transactional(readOnly = true)
	public List<LedgerRow> ledger() {
		java.util.Map<String, BigDecimal> live = new java.util.HashMap<>();
		return trades.findAll(org.springframework.data.domain.Sort.by(org.springframework.data.domain.Sort.Direction.DESC, "entryAt", "id"))
				.stream().map(t -> {
					boolean open = t.getStatus() == SimulatedTrade.Status.OPEN;
					BigDecimal current = open ? live.computeIfAbsent(t.getTicker(), k -> prices.latestPrice(k).orElse(null)) : null;
					BigDecimal unrealized = current == null ? null
							: current.subtract(t.getEntryPrice()).divide(t.getEntryPrice(), 6, java.math.RoundingMode.HALF_UP)
									.multiply(BigDecimal.valueOf(100L * t.getDirection().sign())).setScale(2, java.math.RoundingMode.HALF_UP);
					BigDecimal pnl = t.getReturnPct() == null ? null
							: t.getNotional().multiply(t.getReturnPct()).divide(BigDecimal.valueOf(100), 2, java.math.RoundingMode.HALF_UP);
					Instant end = open ? Instant.now() : t.getClosedAt();
					return new LedgerRow(t.getId(), t.getTicker(), t.getDirection().name(),
							t.getEntryAt().isBefore(NEW_SYSTEM_SINCE) ? "OLD" : "CURRENT", t.getStatus().name(), t.getEntryAt(),
							t.getEntryPrice(), t.getShares(), t.getNotional(), t.getStopPrice(), t.getTargetPrice(), t.getClosedAt(),
							t.getExitPrice(), open ? null : t.getExitReason(),
							end == null ? null : java.time.Duration.between(t.getEntryAt(), end).toDays(), t.getReturnPct(), pnl,
							t.getExcessReturnPct(), t.getWon(), t.isScaleIn(), t.getParentTradeId() != null, current, unrealized,
							t.getReview(), t.getPatternAdvice());
				}).toList();
	}

	/** The live open book: positions grouped by ticker, marked to market against current prices. */
	private OpenBook openBook() {
		List<SimulatedTrade> open = trades.findByStatus(SimulatedTrade.Status.OPEN);

		java.util.Map<String, List<SimulatedTrade>> byTicker = new java.util.LinkedHashMap<>();
		BigDecimal deployed = BigDecimal.ZERO;
		for (SimulatedTrade t : open) {
			deployed = deployed.add(t.getNotional());
			byTicker.computeIfAbsent(t.getTicker(), k -> new java.util.ArrayList<>()).add(t);
		}

		List<OpenPositionView> rows = new java.util.ArrayList<>();
		BigDecimal pricedNotional = BigDecimal.ZERO;
		BigDecimal unrealDollars = BigDecimal.ZERO;
		for (var e : byTicker.entrySet()) {
			List<SimulatedTrade> lots = e.getValue();
			BigDecimal current = prices.latestPrice(e.getKey()).orElse(null);
			boolean priced = current != null && current.signum() > 0;

			BigDecimal tickerNotional = BigDecimal.ZERO;
			BigDecimal tickerUnreal = BigDecimal.ZERO; // dollars
			for (SimulatedTrade t : lots) {
				tickerNotional = tickerNotional.add(t.getNotional());
				if (priced) {
					tickerUnreal = tickerUnreal.add(t.getNotional().multiply(signedReturn(t, current))
							.divide(BigDecimal.valueOf(100), 6, java.math.RoundingMode.HALF_UP));
				}
			}
			BigDecimal tickerPct = priced && tickerNotional.signum() != 0
					? tickerUnreal.multiply(BigDecimal.valueOf(100))
							.divide(tickerNotional, 2, java.math.RoundingMode.HALF_UP)
					: null;
			rows.add(new OpenPositionView(e.getKey(), lots.get(0).getDirection().name(), lots.size(),
					money(tickerNotional), current, tickerPct));
			if (priced) {
				pricedNotional = pricedNotional.add(tickerNotional);
				unrealDollars = unrealDollars.add(tickerUnreal);
			}
		}
		rows.sort(java.util.Comparator.comparing(OpenPositionView::notional).reversed());

		BigDecimal unrealizedPct = pricedNotional.signum() == 0 ? null
				: unrealDollars.multiply(BigDecimal.valueOf(100))
						.divide(pricedNotional, 2, java.math.RoundingMode.HALF_UP);
		return new OpenBook(open.size(), money(deployed), unrealizedPct, rows);
	}

	/** Direction-adjusted unrealized return %, mark-to-market at {@code current} (caller ensures priced). */
	private static BigDecimal signedReturn(SimulatedTrade t, BigDecimal current) {
		return current.subtract(t.getEntryPrice())
				.divide(t.getEntryPrice(), 6, java.math.RoundingMode.HALF_UP)
				.multiply(BigDecimal.valueOf(t.getDirection().sign() * 100L));
	}

	private static BigDecimal money(BigDecimal v) {
		return v.setScale(2, java.math.RoundingMode.HALF_UP);
	}

	private record OpenBook(long count, BigDecimal deployed, BigDecimal unrealizedPct,
			List<OpenPositionView> byTicker) {
	}

	public record Scoreboard(long openTrades, int closedTrades, int wins, Integer winRatePct,
			BigDecimal notionalPerTrade, BigDecimal deployed, BigDecimal realizedPnl,
			BigDecimal bookReturnPct, BigDecimal openDeployed, BigDecimal openUnrealizedPct,
			List<OpenPositionView> openByTicker, List<ClosedTradeView> recent, ManagementView management) {
	}

	/**
	 * Is active management earning its keep? {@code measured} early exits have reached their original horizon;
	 * {@code avgRealizedPct} is what they actually returned, {@code avgHoldPct} what holding to the horizon would
	 * have — managing helps when the first beats the second.
	 */
	public record ManagementView(int openTotal, int openWithStop, int openTrailing, java.util.Map<String, Integer> exitsByReason,
			int earlyExits, int measured, BigDecimal avgRealizedPct, BigDecimal avgHoldPct) {
	}

	private ManagementView management() {
		List<SimulatedTrade> open = trades.findByStatus(SimulatedTrade.Status.OPEN);
		List<SimulatedTrade> closed = trades.findByStatus(SimulatedTrade.Status.CLOSED);
		java.util.Map<String, Integer> byReason = new java.util.TreeMap<>();
		closed.forEach(t -> byReason.merge(t.getExitReason(), 1, Integer::sum));
		List<SimulatedTrade> early = closed.stream().filter(t -> !"HORIZON".equals(t.getExitReason())).toList();
		List<SimulatedTrade> measured = early.stream().filter(t -> t.getHoldReturnPct() != null && t.getReturnPct() != null).toList();
		return new ManagementView(open.size(), (int) open.stream().filter(t -> t.getStopPrice() != null).count(),
				(int) open.stream().filter(SimulatedTrade::isStopTrailed).count(), byReason, early.size(), measured.size(),
				average(measured.stream().map(SimulatedTrade::getReturnPct).toList()),
				average(measured.stream().map(SimulatedTrade::getHoldReturnPct).toList()));
	}

	private static BigDecimal average(List<BigDecimal> values) {
		if (values.isEmpty()) {
			return null;
		}
		return values.stream().reduce(BigDecimal.ZERO, BigDecimal::add)
				.divide(BigDecimal.valueOf(values.size()), 2, java.math.RoundingMode.HALF_UP);
	}

	/** An open position aggregated per ticker, marked to market ({@code unrealizedPct} null if unpriced). */
	public record OpenPositionView(String ticker, String direction, int positions, BigDecimal notional,
			BigDecimal currentPrice, BigDecimal unrealizedPct) {
	}

	/** {@code excessReturnPct} is the vs-SPY figure that decided the win; null when unbenchmarked. */
	public record ClosedTradeView(String ticker, String direction, BigDecimal returnPct,
			BigDecimal excessReturnPct, int horizonDays, boolean won, Instant closedAt, String review, String exitReason) {

		static ClosedTradeView from(SimulatedTrade t) {
			return new ClosedTradeView(t.getTicker(), t.getDirection().name(), t.getReturnPct(),
					t.getExcessReturnPct(), t.getHorizonDays(), Boolean.TRUE.equals(t.getWon()),
					t.getClosedAt(), t.getReview(), t.getExitReason());
		}
	}
}
