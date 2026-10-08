package com.argus.recommendation;

import com.argus.calendar.EarningsQuietPeriodService;
import com.argus.calendar.QuietPeriodStatus;
import com.argus.marketdata.MarketClock;
import com.argus.technical.ChartStudy;
import com.argus.technical.LivePriceService;
import java.math.BigDecimal;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Watches every open paper position through the trading day instead of leaving it until its horizon: every
 * five minutes while the US market is open it applies {@link PositionRules} — hard and trailing stops,
 * breakeven, half off at the target, earnings tightening — at the live price, with Agent 10's levels
 * measured from that price. A position that has no stop yet (opened before stops existed) is given one
 * from today's price first. Each change is logged; exits go through the investor like any other close.
 */
@Component
public class PositionManager {

	private static final Logger log = LoggerFactory.getLogger(PositionManager.class);

	private final SimulatedTradeRepository trades;
	private final LivePriceService prices;
	private final PaperInvestorService investor;
	private final EarningsQuietPeriodService quietPeriod;
	private final MarketClock clock;

	/** Off in tests so no management pass runs against the shared test database. */
	@Value("${argus.paper-investor.manage-enabled:true}")
	private boolean enabled = true;

	public PositionManager(SimulatedTradeRepository trades, LivePriceService prices, PaperInvestorService investor,
			EarningsQuietPeriodService quietPeriod, MarketClock clock) {
		this.trades = trades;
		this.prices = prices;
		this.investor = investor;
		this.quietPeriod = quietPeriod;
		this.clock = clock;
	}

	@Scheduled(fixedDelay = 300_000, initialDelay = 150_000)
	public void scheduledPass() {
		Instant now = Instant.now();
		if (enabled && clock.isRegularHours(now)) {
			manageAll(now);
		}
	}

	void manageAll(Instant now) {
		for (SimulatedTrade trade : trades.findByStatus(SimulatedTrade.Status.OPEN)) {
			try {
				manage(trade, now);
			}
			catch (RuntimeException ex) {
				log.warn("Position manager: {} ({}) failed: {}", trade.getId(), trade.getTicker(), ex.getMessage());
			}
		}
	}

	void manage(SimulatedTrade trade, Instant now) {
		BigDecimal price = prices.latestPrice(trade.getTicker()).orElse(null);
		if (price == null || price.signum() <= 0) {
			return;
		}
		ChartStudy chart = investor.chartAt(trade.getTicker(), price);
		if (trade.getStopPrice() == null) {
			// Opened before stops existed: protect it from today's price, the same chart rule a new entry gets.
			trade.backfillStop(StopLoss.stopFor(trade.getDirection(), chart, price.doubleValue()));
			if (trade.getHighWater() == null) {
				trade.setHighWater(price);
			}
			trades.save(trade);
			log.info("Position manager: gave {} {} its first stop at {} (price {})", trade.getDirection(), trade.getTicker(),
					trade.getStopPrice(), price);
			return;
		}
		if (trade.getTargetPrice() == null && !trade.isScaledOut()) {
			// A current-system leg opened before targets were stored: derive it once from its own recommendation
			// (the card's sell target). Old-system legs have no actionable call behind them and just trail.
			BigDecimal target = investor.targetFor(trade);
			if (target != null) {
				trade.setTargetPrice(target);
				trades.save(trade);
				log.info("Position manager: {} {} take-profit target set at {}", trade.getDirection(), trade.getTicker(), target);
			}
		}
		boolean earningsSoon = quietPeriod.statusFor(trade.getTicker()).status() == QuietPeriodStatus.Status.QUIET;
		PositionRules.Market market = new PositionRules.Market(price.doubleValue(), chart == null ? null : chart.atrPct(),
				chart == null ? null : chart.support(), chart == null ? null : chart.resistance(), earningsSoon);
		PositionRules.Decision d = PositionRules.evaluate(PositionRules.of(trade), market, now);

		switch (d.action()) {
			case EXIT -> {
				log.info("Position manager: {} {} hit its {} ({}) at {}", trade.getDirection(), trade.getTicker(),
						d.exitReason().equals("TRAILING_STOP") ? "trailing stop" : "stop", trade.getStopPrice(), price);
				investor.closeEarly(trade, price, d.exitReason());
			}
			case TAKE_HALF -> investor.takeHalf(trade, price, d.stop() == null ? null : PaperInvestorService.money(d.stop()));
			case HOLD -> {
				boolean changed = false;
				if (d.highWater() != null && (trade.getHighWater() == null || d.highWater() != trade.getHighWater().doubleValue())) {
					trade.setHighWater(PaperInvestorService.money(d.highWater()));
					changed = true;
				}
				BigDecimal current = trade.getStopPrice();
				BigDecimal proposed = d.stop() == null ? null : PaperInvestorService.money(d.stop());
				// Compared at the stored precision: float noise in the rule's arithmetic is not a stop move.
				if (proposed != null && (current == null || proposed.compareTo(current) != 0)) {
					trade.moveStop(proposed);
					changed = true;
					log.info("Position manager: {} {} stop tightened {} → {} (price {}{})", trade.getDirection(), trade.getTicker(),
							current, trade.getStopPrice(), price, earningsSoon ? ", earnings ahead" : "");
				}
				if (changed) {
					trades.save(trade);
				}
			}
		}
	}
}
