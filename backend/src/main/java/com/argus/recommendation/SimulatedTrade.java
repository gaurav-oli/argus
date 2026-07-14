package com.argus.recommendation;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;

/**
 * One position in the Investor persona's paper-trading book (FR-11 follow-up). When the Analyst
 * (Agent 5) makes a recommendation, the Investor opens a fixed-notional simulated position at the
 * current price; after the recommendation's horizon it is marked to market. A trade "wins" on a
 * positive <em>direction-adjusted excess</em> return over the SPY benchmark captured at entry/exit —
 * a bullish call wins when the stock beats the market, a bearish call when it lags it — so the
 * learning loop credits signal quality, not market beta. When no benchmark was captured (Finnhub
 * unavailable) it falls back to the absolute direction-adjusted return. Repeat recommendations on an
 * already-open thesis don't duplicate the position; they bump {@code reaffirmations} instead.
 */
@Entity
@Table(name = "simulated_trades")
public class SimulatedTrade {

	public enum Status {
		OPEN, CLOSED
	}

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "recommendation_id")
	private Long recommendationId;

	@Column(nullable = false)
	private String ticker;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private SignalDirection direction;

	@Column(nullable = false)
	private BigDecimal notional;

	@Column(name = "entry_price", nullable = false)
	private BigDecimal entryPrice;

	@Column(nullable = false)
	private BigDecimal shares;

	@Column(name = "entry_at", nullable = false)
	private Instant entryAt = Instant.now();

	@Column(name = "horizon_days", nullable = false)
	private int horizonDays;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private Status status = Status.OPEN;

	@Column(name = "exit_price")
	private BigDecimal exitPrice;

	@Column(name = "return_pct")
	private BigDecimal returnPct;

	@Column
	private Boolean won;

	@Column
	private String review;

	@Column(name = "closed_at")
	private Instant closedAt;

	/** SPY at entry, for benchmark-relative scoring; null when Finnhub was unavailable at open. */
	@Column(name = "benchmark_entry")
	private BigDecimal benchmarkEntry;

	@Column(name = "benchmark_exit")
	private BigDecimal benchmarkExit;

	/** Direction-adjusted return in excess of SPY over the same window; null when unbenchmarked. */
	@Column(name = "excess_return_pct")
	private BigDecimal excessReturnPct;

	/** How many later recommendations re-affirmed this open thesis instead of duplicating it. */
	@Column(nullable = false)
	private int reaffirmations;

	protected SimulatedTrade() {
		// JPA
	}

	public SimulatedTrade(Long recommendationId, String ticker, SignalDirection direction,
			BigDecimal notional, BigDecimal entryPrice, int horizonDays, BigDecimal benchmarkEntry) {
		this.recommendationId = recommendationId;
		this.ticker = ticker;
		this.direction = direction;
		this.notional = notional;
		this.entryPrice = entryPrice;
		this.shares = notional.divide(entryPrice, 8, RoundingMode.HALF_UP);
		this.horizonDays = horizonDays;
		this.benchmarkEntry = benchmarkEntry;
	}

	/** True once {@code entryAt + horizonDays} has passed — the trade is due to be marked to market. */
	public boolean isDue(Instant now) {
		return status == Status.OPEN && !now.isBefore(entryAt.plus(java.time.Duration.ofDays(horizonDays)));
	}

	/**
	 * Mark this open position to market at {@code exit}: compute the direction-adjusted return and —
	 * when a SPY benchmark bracket exists — the direction-adjusted <em>excess</em> return over SPY,
	 * which decides win/loss (a bullish call wins by beating the market, a bearish call by lagging
	 * it). Without a benchmark ({@code benchmarkExit} null or no entry captured) win/loss falls back
	 * to the absolute direction-adjusted return.
	 */
	public void close(BigDecimal exit, BigDecimal benchmarkExit) {
		this.exitPrice = exit;
		BigDecimal rawPct = pctChange(entryPrice, exit);
		this.returnPct = rawPct.multiply(BigDecimal.valueOf(direction.sign()))
				.setScale(4, RoundingMode.HALF_UP);
		if (benchmarkEntry != null && benchmarkEntry.signum() > 0
				&& benchmarkExit != null && benchmarkExit.signum() > 0) {
			this.benchmarkExit = benchmarkExit;
			BigDecimal spyPct = pctChange(benchmarkEntry, benchmarkExit);
			this.excessReturnPct = rawPct.subtract(spyPct)
					.multiply(BigDecimal.valueOf(direction.sign()))
					.setScale(4, RoundingMode.HALF_UP);
			this.won = excessReturnPct.signum() > 0;
		}
		else {
			this.won = returnPct.signum() > 0;
		}
		this.status = Status.CLOSED;
		this.closedAt = Instant.now();
	}

	/** Another recommendation restated this open thesis — count it rather than duplicate the trade. */
	public void reaffirm() {
		this.reaffirmations++;
	}

	private static BigDecimal pctChange(BigDecimal from, BigDecimal to) {
		return to.subtract(from).divide(from, 6, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100));
	}

	public void recordReview(String review) {
		this.review = review;
	}

	public Long getId() {
		return id;
	}

	public Long getRecommendationId() {
		return recommendationId;
	}

	public String getTicker() {
		return ticker;
	}

	public SignalDirection getDirection() {
		return direction;
	}

	public BigDecimal getNotional() {
		return notional;
	}

	public BigDecimal getEntryPrice() {
		return entryPrice;
	}

	public BigDecimal getShares() {
		return shares;
	}

	public Instant getEntryAt() {
		return entryAt;
	}

	public int getHorizonDays() {
		return horizonDays;
	}

	public Status getStatus() {
		return status;
	}

	public BigDecimal getExitPrice() {
		return exitPrice;
	}

	public BigDecimal getReturnPct() {
		return returnPct;
	}

	public Boolean getWon() {
		return won;
	}

	public String getReview() {
		return review;
	}

	public Instant getClosedAt() {
		return closedAt;
	}

	public BigDecimal getBenchmarkEntry() {
		return benchmarkEntry;
	}

	public BigDecimal getBenchmarkExit() {
		return benchmarkExit;
	}

	public BigDecimal getExcessReturnPct() {
		return excessReturnPct;
	}

	public int getReaffirmations() {
		return reaffirmations;
	}
}
