package com.argus.sec;

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
 * One SEC EDGAR filing for a held ticker (Agent 4 — Financial Reports). Currently insider Form 4s,
 * summarized to the filing's dominant transaction ({@code transactionType} = BUY/SELL/GRANT/OTHER).
 * Dedup key is the {@code accession} number.
 */
@Entity
@Table(name = "sec_filings")
public class SecFiling {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false)
	private String ticker;

	@Column(nullable = false)
	private String cik;

	@Column(nullable = false)
	private String accession;

	@Column(name = "form_type", nullable = false)
	private String formType;

	@Column(name = "filed_at")
	private LocalDate filedAt;

	private String url;

	@Column(name = "insider_name")
	private String insiderName;

	@Column(name = "insider_title")
	private String insiderTitle;

	@Column(name = "transaction_type")
	private String transactionType;

	private BigDecimal shares;

	private BigDecimal value;

	@Column(name = "ingested_at", nullable = false)
	private Instant ingestedAt = Instant.now();

	protected SecFiling() {
		// JPA
	}

	public SecFiling(String ticker, String cik, String accession, String formType, LocalDate filedAt,
			String url, String insiderName, String insiderTitle, String transactionType, BigDecimal shares,
			BigDecimal value) {
		this.ticker = ticker;
		this.cik = cik;
		this.accession = accession;
		this.formType = formType;
		this.filedAt = filedAt;
		this.url = url;
		this.insiderName = insiderName;
		this.insiderTitle = insiderTitle;
		this.transactionType = transactionType;
		this.shares = shares;
		this.value = value;
	}

	public String getTicker() {
		return ticker;
	}

	public String getFormType() {
		return formType;
	}

	public LocalDate getFiledAt() {
		return filedAt;
	}

	public String getUrl() {
		return url;
	}

	public String getInsiderName() {
		return insiderName;
	}

	public String getInsiderTitle() {
		return insiderTitle;
	}

	public String getTransactionType() {
		return transactionType;
	}

	public BigDecimal getShares() {
		return shares;
	}

	public BigDecimal getValue() {
		return value;
	}
}
