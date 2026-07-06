package com.argus.portfolio;

import com.argus.common.LivePushService;
import com.argus.marketdata.FxRateService;
import com.argus.marketdata.MarketClock;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Real-time portfolio valuation (Stories 3.4/3.5, FR-2/FR-3). Holds the latest price (and previous
 * close) per ticker in memory; each tick recomputes a {@link PortfolioSnapshot} — per-position
 * market value, day P&L (vs previous close), total P&L (%), CAD equivalents, and portfolio weight —
 * and pushes it to {@code /topic/portfolio}. CAD uses a recent Bank-of-Canada USD/CAD; cost comes
 * from the Story-3.2 caches. Transient (never persisted) and unit-testable via {@link #onPriceTick}
 * + {@link #recordPreviousClose} (no socket).
 */
@Service
public class LivePortfolioService {

	private static final ZoneId TORONTO = ZoneId.of("America/Toronto");
	private static final String TOPIC = "/topic/portfolio";
	private static final BigDecimal HUNDRED = new BigDecimal("100");

	private final PositionRepository positions;
	private final FxRateService fx;
	private final MarketClock marketClock;
	private final LivePushService livePush;
	private final CashService cash;

	private final Map<String, PricePoint> prices = new ConcurrentHashMap<>();
	private final Map<String, BigDecimal> previousCloses = new ConcurrentHashMap<>();

	public LivePortfolioService(PositionRepository positions, FxRateService fx, MarketClock marketClock,
			LivePushService livePush, CashService cash) {
		this.positions = positions;
		this.fx = fx;
		this.marketClock = marketClock;
		this.livePush = livePush;
		this.cash = cash;
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

	/** Re-broadcast the current snapshot (Story 3.7 — after a manual change, for immediate UI update). */
	public void pushCurrent() {
		livePush.publish(TOPIC, currentSnapshot());
	}

	/** Latest known price for a ticker, if the feed has delivered one (e.g. for the paper-investor book). */
	public java.util.Optional<BigDecimal> latestPrice(String ticker) {
		if (ticker == null) {
			return java.util.Optional.empty();
		}
		PricePoint pp = prices.get(ticker.trim().toUpperCase());
		return java.util.Optional.ofNullable(pp == null ? null : pp.price());
	}

	/** Held tickers with no live price yet — candidates for a supplemental (e.g. TSX) price source. */
	@Transactional(readOnly = true)
	public java.util.Set<String> unpricedHeldTickers() {
		return positions.findAllByOrderByTickerAsc().stream()
				.map(Position::getTicker)
				.filter(t -> t != null && !prices.containsKey(t.trim().toUpperCase()))
				.collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
	}

	/** Record a ticker's previous close (for day P&L). Does not itself push a snapshot. */
	public void recordPreviousClose(String ticker, BigDecimal previousClose) {
		if (ticker != null && previousClose != null) {
			previousCloses.put(ticker.trim().toUpperCase(), previousClose);
		}
	}

	/** Current valuation from the latest known prices. Unpriced tickers have null price/value. */
	@Transactional(readOnly = true)
	public PortfolioSnapshot currentSnapshot() {
		BigDecimal usdCad = fx.usdCadOn(LocalDate.now(TORONTO)).orElse(null);
		List<PositionValue> rows = new ArrayList<>();
		BigDecimal totalValueCad = BigDecimal.ZERO;
		BigDecimal totalCostCad = BigDecimal.ZERO;
		boolean anyAfterHours = false;
		Instant asOf = Instant.now();

		for (Position p : positions.findAllByOrderByTickerAsc()) {
			PricePoint pp = prices.get(p.getTicker());
			BigDecimal price = pp == null ? null : pp.price();
			BigDecimal shares = p.getShares();
			BigDecimal costBasis = p.getCostBasis();
			BigDecimal prevClose = previousCloses.get(p.getTicker());

			BigDecimal marketValue = (price != null && shares != null) ? money(shares.multiply(price)) : null;
			BigDecimal totalPnl = (marketValue != null && costBasis != null)
					? money(marketValue.subtract(costBasis)) : null;
			BigDecimal totalPnlPercent = (totalPnl != null && isPositive(costBasis))
					? percent(totalPnl, costBasis) : null;
			BigDecimal dayPnl = (price != null && prevClose != null && shares != null)
					? money(price.subtract(prevClose).multiply(shares)) : null;
			BigDecimal dayPnlPercent = (price != null && isPositive(prevClose))
					? percent(price.subtract(prevClose), prevClose) : null;

			boolean afterHours = pp != null && pp.afterHours();
			BigDecimal cadMarketValue = toCad(marketValue, p.getCostBasisCurrency(), usdCad);
			BigDecimal cadAcb = p.getCadAcb();
			BigDecimal cadPnl = (cadMarketValue != null && cadAcb != null)
					? money(cadMarketValue.subtract(cadAcb)) : null;
			// USD market value: native for USD holdings, else convert the CAD value back to USD.
			BigDecimal usdMarketValue = "USD".equalsIgnoreCase(p.getCostBasisCurrency()) ? marketValue
					: (cadMarketValue != null && isPositive(usdCad))
							? money(cadMarketValue.divide(usdCad, 6, java.math.RoundingMode.HALF_UP)) : null;

			rows.add(new PositionValue(p.getTicker(), p.getCompanyName(), shares, price, marketValue,
					costBasis, totalPnl, totalPnlPercent, prevClose, dayPnl, dayPnlPercent,
					p.getCostBasisCurrency(), cadMarketValue, cadPnl, null, afterHours,
					pp == null ? null : pp.asOf(), p.getInstitution(), p.getAccount(),
					p.getId(), usdMarketValue, cadAcb, p.isFxEstimated()));

			// Total value (and the weight base) is the sum of EVERY priced position — including
			// FX-estimated ones (cadAcb null) — so weights sum to 100%. Cost only sums where the CAD
			// ACB is known; P&L is value minus known cost.
			if (cadMarketValue != null) {
				totalValueCad = totalValueCad.add(cadMarketValue);
				anyAfterHours = anyAfterHours || afterHours;
			}
			if (cadAcb != null && cadMarketValue != null) {
				totalCostCad = totalCostCad.add(cadAcb);
			}
		}

		// Fold in uninvested cash (the brokerage's total includes it). Added to value AND cost equally
		// so it doesn't distort P&L — cash is worth exactly its face value.
		BigDecimal cashCad = cash.totalCad(usdCad);
		if (cashCad != null && cashCad.signum() > 0) {
			totalValueCad = totalValueCad.add(cashCad);
			totalCostCad = totalCostCad.add(cashCad);
		}

		// Second pass: weight needs the portfolio total, known only after the first pass.
		BigDecimal total = totalValueCad;
		List<PositionValue> withWeights = rows.stream()
				.map(r -> (r.cadMarketValue() != null && isPositive(total))
						? r.withWeight(percent(r.cadMarketValue(), total)) : r)
				.toList();

		return new PortfolioSnapshot(money(totalValueCad), money(totalCostCad),
				money(totalValueCad.subtract(totalCostCad)), anyAfterHours, asOf, withWeights);
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

	private static boolean isPositive(BigDecimal v) {
		return v != null && v.signum() > 0;
	}

	/** {@code numerator / denominator × 100}, scale 2. Caller guarantees denominator &gt; 0. */
	private static BigDecimal percent(BigDecimal numerator, BigDecimal denominator) {
		return numerator.divide(denominator, 8, RoundingMode.HALF_UP).multiply(HUNDRED)
				.setScale(2, RoundingMode.HALF_UP);
	}

	private static BigDecimal money(BigDecimal v) {
		return v == null ? null : v.setScale(2, RoundingMode.HALF_UP);
	}

	/** Latest price for a ticker with its session + timestamp. */
	record PricePoint(BigDecimal price, Instant asOf, boolean afterHours) {
	}
}
