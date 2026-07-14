package com.argus.recommendation;

import com.argus.marketdata.BenchmarkPriceSource;
import com.argus.model.ModelGateway;
import com.argus.portfolio.LivePortfolioService;
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
 * come from the live feed via {@link LivePortfolioService}; SPY via {@link BenchmarkPriceSource}.
 */
@Service
public class PaperInvestorService {

	private static final Logger log = LoggerFactory.getLogger(PaperInvestorService.class);
	private static final List<Integer> DEFAULT_HORIZONS = List.of(7, 30, 90);

	private final SimulatedTradeRepository trades;
	private final LivePortfolioService prices;
	private final BenchmarkPriceSource benchmark;
	private final GraduationService graduation;
	private final TradeConfirmationService confirmations;
	private final ModelGateway gateway;
	private final BigDecimal notional;
	private final List<Integer> horizons;

	public PaperInvestorService(SimulatedTradeRepository trades, LivePortfolioService prices,
			BenchmarkPriceSource benchmark, GraduationService graduation,
			TradeConfirmationService confirmations, ModelGateway gateway,
			@Value("${argus.paper-investor.notional:100}") BigDecimal notional,
			@Value("${argus.paper-investor.horizon-days-list:}") String horizonList,
			@Value("${argus.paper-investor.horizon-days:0}") int legacySingleHorizon) {
		this.trades = trades;
		this.prices = prices;
		this.benchmark = benchmark;
		this.graduation = graduation;
		this.confirmations = confirmations;
		this.gateway = gateway;
		this.notional = notional;
		this.horizons = resolveHorizons(horizonList, legacySingleHorizon);
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
	 * No-op for a NEUTRAL call or an unpriced ticker. Best-effort — never breaks the trigger.
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
				log.debug("Investor: no live price for {} — not opening a paper trade", rec.getTicker());
				return List.of();
			}
			BigDecimal spy = benchmark.latest().orElse(null);

			List<SimulatedTrade> opened = new java.util.ArrayList<>();
			for (int horizon : horizons) {
				if (trades.existsByTickerAndDirectionAndHorizonDaysAndStatus(
						rec.getTicker(), rec.getDirection(), horizon, SimulatedTrade.Status.OPEN)) {
					continue; // this leg of the thesis is already on the book
				}
				opened.add(trades.save(new SimulatedTrade(rec.getId(), rec.getTicker(), rec.getDirection(),
						notional, entry, horizon, spy)));
			}
			if (opened.isEmpty()) {
				List<SimulatedTrade> existing = trades.findByTickerAndDirectionAndStatus(
						rec.getTicker(), rec.getDirection(), SimulatedTrade.Status.OPEN);
				existing.forEach(SimulatedTrade::reaffirm);
				trades.saveAll(existing);
				log.info("Investor: {} {} thesis already open ({} legs) — re-affirmed",
						rec.getDirection(), rec.getTicker(), existing.size());
			}
			else {
				log.info("Investor opened {} × ${} {} leg(s) on {} @ {} (horizons {}, SPY {})",
						opened.size(), notional, rec.getDirection(), rec.getTicker(), entry,
						opened.stream().map(t -> String.valueOf(t.getHorizonDays()))
								.reduce((a, b) -> a + "/" + b).orElse("-"),
						spy == null ? "n/a" : spy);
			}
			return opened;
		} catch (RuntimeException ex) {
			log.warn("Investor: failed to open paper trade for {}: {}",
					rec == null ? "?" : rec.getTicker(), ex.getMessage());
			return List.of();
		}
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
			if (!trade.isDue(now)) {
				continue;
			}
			BigDecimal exit = prices.latestPrice(trade.getTicker()).orElse(null);
			if (exit == null || exit.signum() <= 0) {
				log.debug("Investor: {} due but unpriced — retrying next pass", trade.getTicker());
				continue;
			}
			try {
				closeOne(trade, exit, spy);
			} catch (RuntimeException ex) {
				log.warn("Investor: failed to close paper trade {} ({}): {}",
						trade.getId(), trade.getTicker(), ex.getMessage());
			}
		}
	}

	/**
	 * Close a single trade, feed the outcome to graduation, and reflect on losses. The trade row (with
	 * its win/loss) and {@link GraduationService#recordOutcome} each persist in their own transaction;
	 * the row is saved first so the scoreboard stays correct even if the graduation feed hiccups.
	 */
	private void closeOne(SimulatedTrade trade, BigDecimal exit, BigDecimal benchmarkExit) {
		trade.close(exit, benchmarkExit);
		boolean won = Boolean.TRUE.equals(trade.getWon());
		if (!won) {
			trade.recordReview(postMortem(trade));
		}
		trades.save(trade);
		graduation.recordOutcome(won, trade.getRecommendationId());
		// Mirror the realized outcome onto the user's Taken/Declined decision (regret analysis).
		try {
			confirmations.recordOutcomeFromPaperTrade(trade.getRecommendationId(), won);
		} catch (RuntimeException ex) {
			log.debug("Investor: decision-outcome mirror failed for rec {}: {}",
					trade.getRecommendationId(), ex.getMessage());
		}
		log.info("Investor closed {} paper trade on {} ({}d): {}% abs, {} vs SPY ({}) @ {}",
				trade.getDirection(), trade.getTicker(), trade.getHorizonDays(), trade.getReturnPct(),
				trade.getExcessReturnPct() == null ? "unbenchmarked" : trade.getExcessReturnPct() + "%",
				won ? "WON" : "LOST", exit);
	}

	/** Ask the model why a losing call likely went wrong — the Analyst learning from the Investor. */
	private String postMortem(SimulatedTrade t) {
		try {
			String vsMarket = t.getExcessReturnPct() == null ? ""
					: " (%s%% vs the S&P 500 over the same window — the call %s the market)"
							.formatted(t.getExcessReturnPct(),
									t.getExcessReturnPct().signum() > 0 ? "beat" : "lagged");
			String prompt = """
					You are Argus, an investing analyst reviewing your own recommendation that lost money in a \
					paper trade. Be honest and specific, 1-2 sentences, no disclaimers.

					Call: %s on %s
					Entry price: %s, exit after %d days: %s (direction-adjusted return %s%%%s)
					In 1-2 sentences: what most likely went wrong, and what signal you'd weight differently next time?
					Respond with ONLY the reflection text.
					""".formatted(t.getDirection(), t.getTicker(), t.getEntryPrice(), t.getHorizonDays(),
					t.getExitPrice(), t.getReturnPct(), vsMarket);
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
				bookReturnPct, openBook.deployed(), openBook.unrealizedPct(), openBook.byTicker(), recentClosed);
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
			List<OpenPositionView> openByTicker, List<ClosedTradeView> recent) {
	}

	/** An open position aggregated per ticker, marked to market ({@code unrealizedPct} null if unpriced). */
	public record OpenPositionView(String ticker, String direction, int positions, BigDecimal notional,
			BigDecimal currentPrice, BigDecimal unrealizedPct) {
	}

	/** {@code excessReturnPct} is the vs-SPY figure that decided the win; null when unbenchmarked. */
	public record ClosedTradeView(String ticker, String direction, BigDecimal returnPct,
			BigDecimal excessReturnPct, int horizonDays, boolean won, Instant closedAt, String review) {

		static ClosedTradeView from(SimulatedTrade t) {
			return new ClosedTradeView(t.getTicker(), t.getDirection().name(), t.getReturnPct(),
					t.getExcessReturnPct(), t.getHorizonDays(), Boolean.TRUE.equals(t.getWon()),
					t.getClosedAt(), t.getReview());
		}
	}
}
