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
 * positive <em>direction-adjusted</em> return — a bullish call wins when the price rises, a bearish
 * call wins when it falls — measuring whether acting on the recommendation would have made money.
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

	protected SimulatedTrade() {
		// JPA
	}

	public SimulatedTrade(Long recommendationId, String ticker, SignalDirection direction,
			BigDecimal notional, BigDecimal entryPrice, int horizonDays) {
		this.recommendationId = recommendationId;
		this.ticker = ticker;
		this.direction = direction;
		this.notional = notional;
		this.entryPrice = entryPrice;
		this.shares = notional.divide(entryPrice, 8, RoundingMode.HALF_UP);
		this.horizonDays = horizonDays;
	}

	/** True once {@code entryAt + horizonDays} has passed — the trade is due to be marked to market. */
	public boolean isDue(Instant now) {
		return status == Status.OPEN && !now.isBefore(entryAt.plus(java.time.Duration.ofDays(horizonDays)));
	}

	/**
	 * Mark this open position to market at {@code exit}: compute the direction-adjusted return, decide
	 * win/loss, and close it. A bullish call gains when price rises; a bearish call gains when it falls.
	 */
	public void close(BigDecimal exit) {
		this.exitPrice = exit;
		BigDecimal rawPct = exit.subtract(entryPrice)
				.divide(entryPrice, 6, RoundingMode.HALF_UP)
				.multiply(BigDecimal.valueOf(100));
		this.returnPct = rawPct.multiply(BigDecimal.valueOf(direction.sign()))
				.setScale(4, RoundingMode.HALF_UP);
		this.won = returnPct.signum() > 0;
		this.status = Status.CLOSED;
		this.closedAt = Instant.now();
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
}
