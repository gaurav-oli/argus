package com.argus.portfolio;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** Append-only audit entry for a manual position change (Story 3.7, FR-5). */
@Entity
@Table(name = "position_audit")
public class PositionAudit {

	public static final String CREATED = "created";
	public static final String UPDATED = "updated";
	public static final String REMOVED = "removed";

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false)
	private String ticker;

	@Column(nullable = false)
	private String action;

	private String detail;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt = Instant.now();

	protected PositionAudit() {
		// JPA
	}

	public PositionAudit(String ticker, String action, String detail) {
		this.ticker = ticker;
		this.action = action;
		this.detail = detail;
	}

	public Long getId() {
		return id;
	}

	public String getTicker() {
		return ticker;
	}

	public String getAction() {
		return action;
	}

	public String getDetail() {
		return detail;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}
}
