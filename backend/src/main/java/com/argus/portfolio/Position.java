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
 * A single portfolio holding (Story 3.1, FR-1). Cost basis is stored in its original trade
 * currency as {@link BigDecimal} (never float); the CAD ACB at purchase-time FX is added in
 * Story 3.2. {@code needsReview} marks a holding whose import left a field unparsed (FR-1: such
 * fields are flagged for manual entry, never silently dropped).
 */
@Entity
@Table(name = "positions")
public class Position {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false)
	private String ticker;

	@Column(name = "company_name")
	private String companyName;

	private BigDecimal shares;

	@Column(name = "cost_basis")
	private BigDecimal costBasis;

	@Column(name = "cost_basis_currency", nullable = false)
	private String costBasisCurrency = "USD";

	/** Weighted-average CAD ACB across lots at purchase-time FX (Story 3.2); null when not computable. */
	@Column(name = "cad_acb")
	private BigDecimal cadAcb;

	/** True when any contributing lot's purchase FX is an unconfirmed estimate (Story 3.2). */
	@Column(name = "fx_estimated", nullable = false)
	private boolean fxEstimated = false;

	@Column(name = "acquisition_date")
	private LocalDate acquisitionDate;

	@Column(name = "needs_review", nullable = false)
	private boolean needsReview = false;

	/** Provenance: {@code pdf_import} (this story) or {@code manual} (Story 3.7). */
	@Column(nullable = false)
	private String source = "pdf_import";

	@Column(name = "created_at", nullable = false)
	private Instant createdAt = Instant.now();

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt = Instant.now();

	protected Position() {
		// JPA
	}

	public Position(String ticker, String companyName, BigDecimal shares, BigDecimal costBasis,
			String costBasisCurrency, LocalDate acquisitionDate, boolean needsReview, String source) {
		this.ticker = ticker;
		this.companyName = companyName;
		this.shares = shares;
		this.costBasis = costBasis;
		this.costBasisCurrency = costBasisCurrency != null ? costBasisCurrency : "USD";
		this.acquisitionDate = acquisitionDate;
		this.needsReview = needsReview;
		this.source = source;
	}

	/**
	 * Apply recomputed weighted-average ACB caches (Story 3.2 {@code AcbCalculator}). Lots are the
	 * source of truth; these fields are the cached aggregates read by the holdings views.
	 */
	public void updateAcbCaches(BigDecimal shares, BigDecimal costBasis, String costBasisCurrency,
			BigDecimal cadAcb, boolean fxEstimated) {
		this.shares = shares;
		this.costBasis = costBasis;
		this.costBasisCurrency = costBasisCurrency != null ? costBasisCurrency : this.costBasisCurrency;
		this.cadAcb = cadAcb;
		this.fxEstimated = fxEstimated;
		this.updatedAt = Instant.now();
	}

	public Long getId() {
		return id;
	}

	public BigDecimal getCadAcb() {
		return cadAcb;
	}

	public boolean isFxEstimated() {
		return fxEstimated;
	}

	public String getTicker() {
		return ticker;
	}

	public String getCompanyName() {
		return companyName;
	}

	public BigDecimal getShares() {
		return shares;
	}

	public BigDecimal getCostBasis() {
		return costBasis;
	}

	public String getCostBasisCurrency() {
		return costBasisCurrency;
	}

	public LocalDate getAcquisitionDate() {
		return acquisitionDate;
	}

	public boolean isNeedsReview() {
		return needsReview;
	}

	public String getSource() {
		return source;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public Instant getUpdatedAt() {
		return updatedAt;
	}
}
