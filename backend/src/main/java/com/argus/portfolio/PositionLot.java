package com.argus.portfolio;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * One purchase lot of a {@link Position} (Story 3.2, FR-1b). A position's cost basis is the
 * weighted-average across its lots (A-16). Each lot carries its trade-currency cost plus the
 * USD/CAD rate at the trade date; {@code fxEstimated} marks a lot whose purchase FX is a
 * placeholder awaiting confirmation. Money is {@link BigDecimal} (never float).
 */
@Entity
@Table(name = "position_lots")
public class PositionLot {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "position_id", nullable = false)
	private Long positionId;

	@Column(nullable = false)
	private BigDecimal shares;

	/** Lot cost in the original trade currency; null when the import couldn't read it. */
	@Column(name = "total_cost")
	private BigDecimal totalCost;

	@Column(name = "trade_currency", nullable = false)
	private String tradeCurrency = "USD";

	@Column(name = "trade_date")
	private LocalDate tradeDate;

	/** USD/CAD at {@link #tradeDate}; {@code 1} for a CAD lot; null when unknown. */
	@Column(name = "fx_to_cad")
	private BigDecimal fxToCad;

	@Column(name = "fx_estimated", nullable = false)
	private boolean fxEstimated = false;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt = Instant.now();

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt = Instant.now();

	protected PositionLot() {
		// JPA
	}

	public PositionLot(Long positionId, BigDecimal shares, BigDecimal totalCost, String tradeCurrency,
			LocalDate tradeDate, BigDecimal fxToCad, boolean fxEstimated) {
		this.positionId = positionId;
		this.shares = shares;
		this.totalCost = totalCost;
		this.tradeCurrency = tradeCurrency != null ? tradeCurrency : "USD";
		this.tradeDate = tradeDate;
		this.fxToCad = fxToCad;
		this.fxEstimated = fxEstimated;
	}

	/** Set/clear the purchase FX (e.g. on user confirm) and mark whether it's still estimated. */
	public void applyFx(BigDecimal fxToCad, boolean fxEstimated) {
		this.fxToCad = fxToCad;
		this.fxEstimated = fxEstimated;
		this.updatedAt = Instant.now();
	}

	/**
	 * Apply a split/exchange ratio (Story 3.3): scale the share count, leaving {@code totalCost}
	 * unchanged — so total cost basis is preserved and per-share cost adjusts. Ratio &gt; 1 = forward
	 * split, &lt; 1 = reverse split.
	 */
	public void applySplit(BigDecimal ratio) {
		this.shares = this.shares.multiply(ratio);
		this.updatedAt = Instant.now();
	}

	public Long getId() {
		return id;
	}

	public Long getPositionId() {
		return positionId;
	}

	public BigDecimal getShares() {
		return shares;
	}

	public BigDecimal getTotalCost() {
		return totalCost;
	}

	public String getTradeCurrency() {
		return tradeCurrency;
	}

	public LocalDate getTradeDate() {
		return tradeDate;
	}

	public BigDecimal getFxToCad() {
		return fxToCad;
	}

	public boolean isFxEstimated() {
		return fxEstimated;
	}
}
