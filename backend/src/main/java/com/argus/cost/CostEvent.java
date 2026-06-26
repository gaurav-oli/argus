package com.argus.cost;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;

/** A single persisted paid (cloud-model) call (Agent 6 — Cost Governor). */
@Entity
@Table(name = "cost_events")
public class CostEvent {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "occurred_at", nullable = false)
	private Instant occurredAt = Instant.now();

	@Column(nullable = false)
	private String model;

	private String source;

	@Column(name = "input_tokens", nullable = false)
	private long inputTokens;

	@Column(name = "output_tokens", nullable = false)
	private long outputTokens;

	@Column(name = "cost_usd", nullable = false)
	private BigDecimal costUsd;

	protected CostEvent() {
		// JPA
	}

	public CostEvent(String model, String source, long inputTokens, long outputTokens, BigDecimal costUsd) {
		this.model = model;
		this.source = source;
		this.inputTokens = inputTokens;
		this.outputTokens = outputTokens;
		this.costUsd = costUsd;
	}
}
