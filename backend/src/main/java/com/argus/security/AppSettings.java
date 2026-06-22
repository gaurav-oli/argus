package com.argus.security;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * Single-row app settings (Story 2.3). {@code sessionTimeoutSeconds == null} means "Never"
 * (no idle expiry). Future stories add columns to this row.
 */
@Entity
@Table(name = "app_settings")
public class AppSettings {

	static final short SINGLETON_ID = 1;

	@Id
	private short id = SINGLETON_ID;

	@Column(name = "session_timeout_seconds")
	private Long sessionTimeoutSeconds;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt = Instant.now();

	protected AppSettings() {
		// JPA
	}

	public short getId() {
		return id;
	}

	public Long getSessionTimeoutSeconds() {
		return sessionTimeoutSeconds;
	}

	public void setSessionTimeoutSeconds(Long sessionTimeoutSeconds) {
		this.sessionTimeoutSeconds = sessionTimeoutSeconds;
		this.updatedAt = Instant.now();
	}

	public Instant getUpdatedAt() {
		return updatedAt;
	}
}
