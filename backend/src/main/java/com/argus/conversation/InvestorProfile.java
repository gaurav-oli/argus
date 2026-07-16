package com.argus.conversation;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * Single-row, user-editable investor profile (Story 7.6) — the parts of the profile that can't be
 * derived from imported accounts. Every field is nullable; a blank profile falls back to the
 * {@code argus.investor.*} config defaults (residency/home currency) and the account-derived facts, so
 * the pre-7.6 behavior is unchanged until the user edits it. Follows the {@code AppSettings} singleton
 * pattern (fixed id = 1, DB {@code CHECK (id = 1)}). Setters stamp {@code updatedAt}.
 */
@Entity
@Table(name = "investor_profile")
public class InvestorProfile {

	static final short SINGLETON_ID = 1;

	@Id
	private short id = SINGLETON_ID;

	@Enumerated(EnumType.STRING)
	@Column(name = "risk_tolerance")
	private RiskTolerance riskTolerance;

	@Column(name = "financial_goal")
	private String financialGoal;

	@Column(name = "target_amount")
	private BigDecimal targetAmount;

	@Column(name = "target_date")
	private LocalDate targetDate;

	@Column(name = "residency")
	private String residency;

	@Column(name = "home_currency")
	private String homeCurrency;

	@Column(name = "notes")
	private String notes;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt = Instant.now();

	protected InvestorProfile() {
		// JPA
	}

	public short getId() {
		return id;
	}

	public RiskTolerance getRiskTolerance() {
		return riskTolerance;
	}

	public void setRiskTolerance(RiskTolerance riskTolerance) {
		this.riskTolerance = riskTolerance;
		this.updatedAt = Instant.now();
	}

	public String getFinancialGoal() {
		return financialGoal;
	}

	public void setFinancialGoal(String financialGoal) {
		this.financialGoal = financialGoal;
		this.updatedAt = Instant.now();
	}

	public BigDecimal getTargetAmount() {
		return targetAmount;
	}

	public void setTargetAmount(BigDecimal targetAmount) {
		this.targetAmount = targetAmount;
		this.updatedAt = Instant.now();
	}

	public LocalDate getTargetDate() {
		return targetDate;
	}

	public void setTargetDate(LocalDate targetDate) {
		this.targetDate = targetDate;
		this.updatedAt = Instant.now();
	}

	public String getResidency() {
		return residency;
	}

	public void setResidency(String residency) {
		this.residency = residency;
		this.updatedAt = Instant.now();
	}

	public String getHomeCurrency() {
		return homeCurrency;
	}

	public void setHomeCurrency(String homeCurrency) {
		this.homeCurrency = homeCurrency;
		this.updatedAt = Instant.now();
	}

	public String getNotes() {
		return notes;
	}

	public void setNotes(String notes) {
		this.notes = notes;
		this.updatedAt = Instant.now();
	}

	public Instant getUpdatedAt() {
		return updatedAt;
	}
}
