package com.argus.recommendation;

import com.argus.model.ModelGateway;
import com.argus.portfolio.LivePortfolioService;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The Investor persona (FR-11 follow-up). It closes the loop on Agent 5's recommendations without any
 * human input: when the Analyst makes a directional call, the Investor {@link #open opens} a simulated
 * fixed-notional position at the current price. A scheduled pass {@link #closeDueTrades marks due
 * positions to market}, decides win/loss by the direction-adjusted return, and feeds that outcome into
 * the existing {@link GraduationService} — so the accuracy/graduation record is built autonomously.
 * On a loss it asks the model for a short post-mortem (the Analyst reflecting on what to watch next
 * time), stored on the trade. Prices come from the live feed via {@link LivePortfolioService}; an
 * unpriced ticker simply isn't traded (or its close is retried next pass).
 */
@Service
public class PaperInvestorService {

	private static final Logger log = LoggerFactory.getLogger(PaperInvestorService.class);

	private final SimulatedTradeRepository trades;
	private final LivePortfolioService prices;
	private final GraduationService graduation;
	private final ModelGateway gateway;
	private final BigDecimal notional;
	private final int horizonDays;

	public PaperInvestorService(SimulatedTradeRepository trades, LivePortfolioService prices,
			GraduationService graduation, ModelGateway gateway,
			@Value("${argus.paper-investor.notional:100}") BigDecimal notional,
			@Value("${argus.paper-investor.horizon-days:30}") int horizonDays) {
		this.trades = trades;
		this.prices = prices;
		this.graduation = graduation;
		this.gateway = gateway;
		this.notional = notional;
		this.horizonDays = horizonDays;
	}

	/**
	 * Open a simulated position for a fresh recommendation. No-op for a NEUTRAL call, an unpriced ticker,
	 * or a recommendation already traded. Best-effort — never lets a paper trade break the trigger.
	 */
	@Transactional
	public Optional<SimulatedTrade> open(Recommendation rec) {
		try {
			if (rec == null || rec.getDirection() == SignalDirection.NEUTRAL || rec.getId() == null) {
				return Optional.empty();
			}
			if (trades.existsByRecommendationId(rec.getId())) {
				return Optional.empty();
			}
			BigDecimal entry = prices.latestPrice(rec.getTicker()).orElse(null);
			if (entry == null || entry.signum() <= 0) {
				log.debug("Investor: no live price for {} — not opening a paper trade", rec.getTicker());
				return Optional.empty();
			}
			SimulatedTrade trade = trades.save(new SimulatedTrade(
					rec.getId(), rec.getTicker(), rec.getDirection(), notional, entry, horizonDays));
			log.info("Investor opened ${} {} paper trade on {} @ {} (horizon {}d)",
					notional, rec.getDirection(), rec.getTicker(), entry, horizonDays);
			return Optional.of(trade);
		} catch (RuntimeException ex) {
			log.warn("Investor: failed to open paper trade for {}: {}",
					rec == null ? "?" : rec.getTicker(), ex.getMessage());
			return Optional.empty();
		}
	}

	/**
	 * Hourly: mark every due position to market. Each close records win/loss into the graduation
	 * machinery and, on a loss, captures the Analyst's post-mortem. A ticker with no live price yet is
	 * left open and retried next pass.
	 */
	@Scheduled(cron = "${argus.paper-investor.close-cron:0 0 * * * *}")
	public void closeDueTrades() {
		Instant now = Instant.now();
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
				closeOne(trade, exit);
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
	private void closeOne(SimulatedTrade trade, BigDecimal exit) {
		trade.close(exit);
		boolean won = Boolean.TRUE.equals(trade.getWon());
		if (!won) {
			trade.recordReview(postMortem(trade));
		}
		trades.save(trade);
		graduation.recordOutcome(won, trade.getRecommendationId());
		log.info("Investor closed {} paper trade on {}: {}% ({}) @ {}",
				trade.getDirection(), trade.getTicker(), trade.getReturnPct(), won ? "WON" : "LOST", exit);
	}

	/** Ask the model why a losing call likely went wrong — the Analyst learning from the Investor. */
	private String postMortem(SimulatedTrade t) {
		try {
			String prompt = """
					You are Argus, an investing analyst reviewing your own recommendation that lost money in a \
					paper trade. Be honest and specific, 1-2 sentences, no disclaimers.

					Call: %s on %s
					Entry price: %s, exit after %d days: %s (direction-adjusted return %s%%)
					In 1-2 sentences: what most likely went wrong, and what signal you'd weight differently next time?
					Respond with ONLY the reflection text.
					""".formatted(t.getDirection(), t.getTicker(), t.getEntryPrice(), t.getHorizonDays(),
					t.getExitPrice(), t.getReturnPct());
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

	public record ClosedTradeView(String ticker, String direction, BigDecimal returnPct, boolean won,
			Instant closedAt, String review) {

		static ClosedTradeView from(SimulatedTrade t) {
			return new ClosedTradeView(t.getTicker(), t.getDirection().name(), t.getReturnPct(),
					Boolean.TRUE.equals(t.getWon()), t.getClosedAt(), t.getReview());
		}
	}
}
