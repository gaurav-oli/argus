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
 * One person's editable investor profile (Story 7.6; made per-person in Phase 2, multi-user) — the
 * parts of the profile that can't be derived from imported accounts. Every field is nullable; a blank
 * profile falls back to the {@code argus.investor.*} config defaults (residency/home currency) and the
 * account-derived facts. Keyed directly by {@code userId} (not a separate generated id) since it is a
 * strict one-row-per-person table — no Hibernate {@code @TenantId} needed here, unlike the portfolio
 * entities, because a lookup is always a direct {@code findById(userId)}, never a list to filter.
 *
 * <p>{@code onboardingCompletedAt} is null until this person has saved a profile at least once (via
 * the first-login questions or later in Settings) — that's the signal the frontend uses to show the
 * onboarding screen exactly once.
 */
@Entity
@Table(name = "investor_profile")
public class InvestorProfile {

	@Id
	@Column(name = "user_id")
	private Long userId;

	@Enumerated(EnumType.STRING)
	@Column(name = "risk_tolerance")
	private RiskTolerance riskTolerance;

	@Enumerated(EnumType.STRING)
	@Column(name = "trading_horizon")
	private TradingHorizon tradingHorizon;

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

	@Column(name = "onboarding_completed_at")
	private Instant onboardingCompletedAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt = Instant.now();

	protected InvestorProfile() {
		// JPA
	}

	public InvestorProfile(Long userId) {
		this.userId = userId;
	}

	public Long getUserId() {
		return userId;
	}

	public RiskTolerance getRiskTolerance() {
		return riskTolerance;
	}

	public void setRiskTolerance(RiskTolerance riskTolerance) {
		this.riskTolerance = riskTolerance;
		this.updatedAt = Instant.now();
	}

	public TradingHorizon getTradingHorizon() {
		return tradingHorizon;
	}

	public void setTradingHorizon(TradingHorizon tradingHorizon) {
		this.tradingHorizon = tradingHorizon;
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

	public Instant getOnboardingCompletedAt() {
		return onboardingCompletedAt;
	}

	/** Mark onboarding done (first save of any kind, or an explicit skip) — idempotent. */
	public void completeOnboarding() {
		if (this.onboardingCompletedAt == null) {
			this.onboardingCompletedAt = Instant.now();
		}
	}

	public Instant getUpdatedAt() {
		return updatedAt;
	}
}
