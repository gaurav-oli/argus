package com.argus.recommendation;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * A user's Taken/Declined decision on a recommendation, with an immutable {@code snapshot} of the
 * signals + reasoning captured at decision time (Story 6.7, FR-14b). The actual {@code outcome} is
 * recorded later without mutating the snapshot.
 */
@Entity
@Table(name = "trade_decisions")
public class TradeDecision {

	public enum Decision { TAKEN, DECLINED }

	public enum Outcome { WIN, LOSS }

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "recommendation_id", nullable = false)
	private Long recommendationId;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private Decision decision;

	@Column(columnDefinition = "text")
	private String reasoning;

	@Column(nullable = false, columnDefinition = "text")
	private String snapshot;

	@Column(name = "decided_at", nullable = false)
	private Instant decidedAt = Instant.now();

	@Enumerated(EnumType.STRING)
	private Outcome outcome;

	@Column(name = "outcome_at")
	private Instant outcomeAt;

	protected TradeDecision() {
		// JPA
	}

	public TradeDecision(Long recommendationId, Decision decision, String reasoning, String snapshot) {
		this.recommendationId = recommendationId;
		this.decision = decision;
		this.reasoning = reasoning;
		this.snapshot = snapshot;
	}

	/** Record the realized outcome once (idempotent — a set outcome is not overwritten). */
	public void recordOutcome(Outcome realized) {
		if (this.outcome == null) {
			this.outcome = realized;
			this.outcomeAt = Instant.now();
		}
	}

	public Long getId() {
		return id;
	}

	public Long getRecommendationId() {
		return recommendationId;
	}

	public Decision getDecision() {
		return decision;
	}

	public String getReasoning() {
		return reasoning;
	}

	public String getSnapshot() {
		return snapshot;
	}

	public Outcome getOutcome() {
		return outcome;
	}
}
