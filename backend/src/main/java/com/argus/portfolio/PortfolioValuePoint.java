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
import org.hibernate.annotations.TenantId;

/** One daily total-portfolio-value point in CAD (Story 3.6, FR-4). One row per {@code capturedOn}
 * PER PERSON (Phase 2, multi-user) — the uniqueness is now the composite {@code (user_id, captured_on)}
 * DB constraint, not this column alone. */
@Entity
@Table(name = "portfolio_value_history")
public class PortfolioValuePoint {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@TenantId
	@Column(name = "user_id", nullable = false, updatable = false)
	private Long userId;

	@Column(name = "captured_on", nullable = false)
	private LocalDate capturedOn;

	@Column(name = "total_value_cad", nullable = false)
	private BigDecimal totalValueCad;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt = Instant.now();

	protected PortfolioValuePoint() {
		// JPA
	}

	public PortfolioValuePoint(LocalDate capturedOn, BigDecimal totalValueCad) {
		this.capturedOn = capturedOn;
		this.totalValueCad = totalValueCad;
	}

	public void setTotalValueCad(BigDecimal totalValueCad) {
		this.totalValueCad = totalValueCad;
	}

	public Long getId() {
		return id;
	}

	public Long getUserId() {
		return userId;
	}

	public LocalDate getCapturedOn() {
		return capturedOn;
	}

	public BigDecimal getTotalValueCad() {
		return totalValueCad;
	}
}
