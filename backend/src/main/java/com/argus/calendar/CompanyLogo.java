package com.argus.calendar;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * Cached ticker -> logo URL lookup (Finnhub {@code /stock/profile2}), populated by
 * {@link CompanyLogoService} during Agent 7's ingest. {@code logoUrl} is nullable — a row with a
 * null URL still records that Finnhub had nothing for this ticker, so ingestion doesn't retry it
 * every run.
 */
@Entity
@Table(name = "company_logo")
public class CompanyLogo {

	@Id
	private String ticker;

	@Column(name = "logo_url")
	private String logoUrl;

	@Column(name = "fetched_at", nullable = false)
	private Instant fetchedAt = Instant.now();

	protected CompanyLogo() {
		// JPA
	}

	public CompanyLogo(String ticker, String logoUrl) {
		this.ticker = ticker;
		this.logoUrl = logoUrl;
	}

	public String getTicker() {
		return ticker;
	}

	public String getLogoUrl() {
		return logoUrl;
	}

	public Instant getFetchedAt() {
		return fetchedAt;
	}
}
