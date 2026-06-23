package com.argus.portfolio;

import com.argus.common.LivePushService;
import com.argus.marketdata.MarketClock;
import com.argus.marketdata.FxRateService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Real-time portfolio valuation (Story 3.4, FR-2). Holds the latest price per ticker in memory;
 * each tick recomputes a {@link PortfolioSnapshot} (per-position market value + P&L, CAD totals)
 * and pushes it to {@code /topic/portfolio} for live UI updates. CAD conversion uses a recent
 * Bank-of-Canada USD/CAD (not per-tick); cost figures come from the Story-3.2 caches. Snapshots are
 * transient — never persisted. The price source is decoupled (see {@code PriceFeed}) so this is
 * fully unit-testable by calling {@link #onPriceTick} directly.
 */
@Service
public class LivePortfolioService {

	private static final ZoneId TORONTO = ZoneId.of("America/Toronto");
	private static final String TOPIC = "/topic/portfolio";

	private final PositionRepository positions;
	private final FxRateService fx;
	private final MarketClock marketClock;
	private final LivePushService livePush;

	private final Map<String, PricePoint> prices = new ConcurrentHashMap<>();

	public LivePortfolioService(PositionRepository positions, FxRateService fx, MarketClock marketClock,
			LivePushService livePush) {
		this.positions = positions;
		this.fx = fx;
		this.marketClock = marketClock;
		this.livePush = livePush;
	}

	/** Record a price tick and push a fresh snapshot. Ticker is normalized to uppercase. */
	@Transactional(readOnly = true)
	public void onPriceTick(String ticker, BigDecimal price, Instant when) {
		if (ticker == null || price == null || when == null) {
			return;
		}
		prices.put(ticker.trim().toUpperCase(),
				new PricePoint(price, when, !marketClock.isRegularHours(when)));
		livePush.publish(TOPIC, currentSnapshot());
	}

	/** Current valuation from the latest known prices. Unpriced tickers have null price/value. */
	@Transactional(readOnly = true)
	public PortfolioSnapshot currentSnapshot() {
		BigDecimal usdCad = fx.usdCadOn(LocalDate.now(TORONTO)).orElse(null);
		List<PositionValue> values = new ArrayList<>();
		BigDecimal totalValueCad = BigDecimal.ZERO;
		BigDecimal totalCostCad = BigDecimal.ZERO;
		boolean anyAfterHours = false;
		Instant asOf = Instant.now();

		for (Position p : positions.findAllByOrderByTickerAsc()) {
			PricePoint pp = prices.get(p.getTicker());
			BigDecimal price = pp == null ? null : pp.price();
			BigDecimal shares = p.getShares();
			BigDecimal marketValue = (price != null && shares != null)
					? money(shares.multiply(price)) : null;
			BigDecimal costBasis = p.getCostBasis();
			BigDecimal totalPnl = (marketValue != null && costBasis != null)
					? money(marketValue.subtract(costBasis)) : null;
			boolean afterHours = pp != null && pp.afterHours();

			BigDecimal cadMarketValue = toCad(marketValue, p.getCostBasisCurrency(), usdCad);
			BigDecimal cadAcb = p.getCadAcb();
			BigDecimal cadPnl = (cadMarketValue != null && cadAcb != null)
					? money(cadMarketValue.subtract(cadAcb)) : null;

			values.add(new PositionValue(p.getTicker(), shares, price, marketValue, costBasis, totalPnl,
					p.getCostBasisCurrency(), cadMarketValue, cadPnl, afterHours, pp == null ? null : pp.asOf()));

			// Totals (CAD) include a position only once it is priced, so value − cost stays consistent.
			if (cadMarketValue != null && cadAcb != null) {
				totalValueCad = totalValueCad.add(cadMarketValue);
				totalCostCad = totalCostCad.add(cadAcb);
				anyAfterHours = anyAfterHours || afterHours;
			}
		}

		return new PortfolioSnapshot(money(totalValueCad), money(totalCostCad),
				money(totalValueCad.subtract(totalCostCad)), anyAfterHours, asOf, values);
	}

	private static BigDecimal toCad(BigDecimal amount, String currency, BigDecimal usdCad) {
		if (amount == null) {
			return null;
		}
		if ("CAD".equalsIgnoreCase(currency)) {
			return money(amount);
		}
		return usdCad == null ? null : money(amount.multiply(usdCad));
	}

	private static BigDecimal money(BigDecimal v) {
		return v == null ? null : v.setScale(2, RoundingMode.HALF_UP);
	}

	/** For tests: latest recorded FX-independent reference (not exposed via API). */
	Optional<PricePoint> latestPrice(String ticker) {
		return Optional.ofNullable(prices.get(ticker.trim().toUpperCase()));
	}

	/** Latest price for a ticker with its session + timestamp. */
	record PricePoint(BigDecimal price, Instant asOf, boolean afterHours) {
	}
}
