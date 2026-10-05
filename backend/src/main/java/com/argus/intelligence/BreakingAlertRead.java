package com.argus.intelligence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * One person's own "Done Reading" of one {@link BreakingAlert} (Phase 2, multi-user). The alert
 * itself is shared market-wide content — every signed-in person sees the same headline/summary —
 * but dismissing it is personal: this is deliberately NOT a field on {@code BreakingAlert} (that
 * was the bug — a single global flag meant one person's dismissal hid the alert for everyone).
 */
@Entity
@Table(name = "breaking_alert_read")
public class BreakingAlertRead {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "alert_id", nullable = false)
	private Long alertId;

	@Column(name = "user_id", nullable = false)
	private Long userId;

	@Column(name = "read_at", nullable = false)
	private Instant readAt = Instant.now();

	protected BreakingAlertRead() {
		// JPA
	}

	public BreakingAlertRead(Long alertId, Long userId) {
		this.alertId = alertId;
		this.userId = userId;
	}

	public Long getId() {
		return id;
	}

	public Long getAlertId() {
		return alertId;
	}

	public Long getUserId() {
		return userId;
	}

	public Instant getReadAt() {
		return readAt;
	}
}
