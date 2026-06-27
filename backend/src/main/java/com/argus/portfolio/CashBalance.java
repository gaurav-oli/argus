package com.argus.portfolio;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;

/** Uninvested cash held in a brokerage account (one row per account + currency). */
@Entity
@Table(name = "cash_balances")
public class CashBalance {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false)
	private String account;

	@Column(nullable = false)
	private String currency;

	@Column(nullable = false)
	private BigDecimal amount = BigDecimal.ZERO;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt = Instant.now();

	protected CashBalance() {
		// JPA
	}

	public CashBalance(String account, String currency, BigDecimal amount) {
		this.account = account;
		this.currency = currency;
		this.amount = amount;
	}

	public Long getId() {
		return id;
	}

	public String getAccount() {
		return account;
	}

	public String getCurrency() {
		return currency;
	}

	public BigDecimal getAmount() {
		return amount;
	}

	public void setAmount(BigDecimal amount) {
		this.amount = amount;
		this.updatedAt = Instant.now();
	}
}
