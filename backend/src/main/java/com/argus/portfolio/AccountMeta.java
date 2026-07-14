package com.argus.portfolio;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * Owner identity for a brokerage account (Portfolio-tab detail), keyed by (institution, account
 * label). Parsed from the statement header on import — {@code ownerType} is {@code Joint} or
 * {@code Solo} and {@code ownerName} the holder name(s). The account number/type/currency live in
 * the {@code account} label itself (see {@link AccountLabels}); this row adds only who owns it.
 */
@Entity
@Table(name = "account_meta")
public class AccountMeta {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	private String institution;

	@Column(nullable = false)
	private String account;

	@Column(name = "owner_type")
	private String ownerType;

	@Column(name = "owner_name")
	private String ownerName;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt = Instant.now();

	protected AccountMeta() {
		// JPA
	}

	public AccountMeta(String institution, String account, String ownerType, String ownerName) {
		this.institution = institution;
		this.account = account;
		this.ownerType = ownerType;
		this.ownerName = ownerName;
	}

	public Long getId() {
		return id;
	}

	public String getInstitution() {
		return institution;
	}

	public String getAccount() {
		return account;
	}

	public String getOwnerType() {
		return ownerType;
	}

	public void setOwnerType(String ownerType) {
		this.ownerType = ownerType;
	}

	public String getOwnerName() {
		return ownerName;
	}

	public void setOwnerName(String ownerName) {
		this.ownerName = ownerName;
		this.updatedAt = Instant.now();
	}
}
