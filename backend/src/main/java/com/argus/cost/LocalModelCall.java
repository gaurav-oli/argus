package com.argus.cost;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** A single successful local-model (Gemma) call — free, but counted alongside {@link CostEvent} (the
 * paid-call record) so the Cost Governor panel can show both, not just spend. */
@Entity
@Table(name = "local_model_calls")
public class LocalModelCall {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "occurred_at", nullable = false)
	private Instant occurredAt = Instant.now();

	@Column(nullable = false)
	private String tier;

	protected LocalModelCall() {
		// JPA
	}

	public LocalModelCall(String tier) {
		this.tier = tier;
	}
}
