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

/** One daily total-portfolio-value point in CAD (Story 3.6, FR-4). One row per {@code capturedOn}. */
@Entity
@Table(name = "portfolio_value_history")
public class PortfolioValuePoint {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "captured_on", nullable = false, unique = true)
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

	public LocalDate getCapturedOn() {
		return capturedOn;
	}

	public BigDecimal getTotalValueCad() {
		return totalValueCad;
	}
}
