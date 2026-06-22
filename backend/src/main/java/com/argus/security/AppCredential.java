package com.argus.security;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * The single owner credential (PIN hash). Argus is single-user, so this table holds exactly
 * one row, pinned to {@code id = 1} (enforced by a DB CHECK in {@code V2__app_credential.sql}).
 * The PIN is persisted only as an Argon2id hash — never plaintext.
 */
@Entity
@Table(name = "app_credential")
public class AppCredential {

	static final short SINGLETON_ID = 1;

	@Id
	private short id = SINGLETON_ID;

	@Column(name = "pin_hash", nullable = false)
	private String pinHash;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt = Instant.now();

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt = Instant.now();

	protected AppCredential() {
		// JPA
	}

	public AppCredential(String pinHash) {
		this.id = SINGLETON_ID;
		this.pinHash = pinHash;
	}

	public short getId() {
		return id;
	}

	public String getPinHash() {
		return pinHash;
	}

	public void setPinHash(String pinHash) {
		this.pinHash = pinHash;
		this.updatedAt = Instant.now();
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public Instant getUpdatedAt() {
		return updatedAt;
	}
}
