package com.argus.portfolio;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * A staged PDF import batch (Story 3.1, FR-1). An upload parses the statement into this batch with
 * status {@code pending}; the parsed preview is held in {@code rawHoldings} (a JSON array of
 * {@link ParsedHolding}). Confirming the batch writes its holdings into {@code positions} — so an
 * upload alone never mutates confirmed positions (FR-1 consequence #3).
 */
@Entity
@Table(name = "portfolio_imports")
public class PortfolioImport {

	public static final String PENDING = "pending";
	public static final String CONFIRMED = "confirmed";
	public static final String DISCARDED = "discarded";

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false)
	private String filename;

	@Column(nullable = false)
	private String status = PENDING;

	/** Parsed preview, serialized as a JSON array of {@link ParsedHolding}. */
	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "raw_holdings", nullable = false)
	private String rawHoldings;

	/** Parsed cash balances, serialized as a JSON array of {@link ParsedCash}; persisted on confirm. */
	@Column(name = "raw_cash", nullable = false)
	private String rawCash = "[]";

	/** Parsed per-account owner metadata, JSON array of {@link ParsedAccount}; applied on confirm. */
	@Column(name = "raw_accounts", nullable = false)
	private String rawAccounts = "[]";

	/** Top-level parse note surfaced to the user (e.g. "no holdings found"); null when clean. */
	private String message;

	/** Bank chosen at upload, applied to every holding on confirm (multi-bank holdings). */
	@Column(name = "institution")
	private String institution;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt = Instant.now();

	@Column(name = "confirmed_at")
	private Instant confirmedAt;

	protected PortfolioImport() {
		// JPA
	}

	public PortfolioImport(String filename, String rawHoldings, String message) {
		this.filename = filename;
		this.rawHoldings = rawHoldings;
		this.message = message;
	}

	public String getInstitution() {
		return institution;
	}

	public void setInstitution(String institution) {
		this.institution = institution;
	}

	public String getRawCash() {
		return rawCash;
	}

	public void setRawCash(String rawCash) {
		this.rawCash = rawCash == null ? "[]" : rawCash;
	}

	public String getRawAccounts() {
		return rawAccounts;
	}

	public void setRawAccounts(String rawAccounts) {
		this.rawAccounts = rawAccounts == null ? "[]" : rawAccounts;
	}

	public void markConfirmed() {
		this.status = CONFIRMED;
		this.confirmedAt = Instant.now();
	}

	public Long getId() {
		return id;
	}

	public String getFilename() {
		return filename;
	}

	public String getStatus() {
		return status;
	}

	public String getRawHoldings() {
		return rawHoldings;
	}

	public String getMessage() {
		return message;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public Instant getConfirmedAt() {
		return confirmedAt;
	}
}
