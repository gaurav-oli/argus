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
 * label). Parsed from the statement header on import — {@code ownerType} is {@code Joint},
 * {@code Solo} or {@code Corporate} and {@code ownerName} the holder name(s). {@code accountType} is
 * the normalized registration type (TFSA/RRSP/RESP/Cash/Corporate/…) used to group accounts across
 * banks; it's stored explicitly because some banks (e.g. RBC) don't carry the type in the account
 * label, so {@link AccountLabels}-from-label parsing isn't enough. Null when the parser couldn't tell.
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

	@Column(name = "account_type")
	private String accountType;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt = Instant.now();

	protected AccountMeta() {
		// JPA
	}

	public AccountMeta(String institution, String account, String ownerType, String ownerName,
			String accountType) {
		this.institution = institution;
		this.account = account;
		this.ownerType = ownerType;
		this.ownerName = ownerName;
		this.accountType = accountType;
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

	public String getAccountType() {
		return accountType;
	}

	public void setAccountType(String accountType) {
		this.accountType = accountType;
		this.updatedAt = Instant.now();
	}
}
