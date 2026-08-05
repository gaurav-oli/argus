package com.argus.recommendation;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * A Taken/Declined decision on a recommendation, with an immutable {@code snapshot} of the signals +
 * reasoning captured at decision time (Story 6.7, FR-14b). The actual {@code outcome} is recorded later
 * without mutating the snapshot. {@code source} distinguishes who decided: the Investor persona acting
 * autonomously on its own paper trades ({@link Source#AGENT}, via
 * {@link TradeConfirmationService#recordAgentDecision}) vs a human confirming a card
 * ({@link Source#USER}, via {@link TradeConfirmationService#confirm}) — both can exist for the same
 * recommendation, since a human weighing in doesn't erase what the algorithm already did.
 */
@Entity
@Table(name = "trade_decisions")
public class TradeDecision {

	public enum Decision { TAKEN, DECLINED }

	public enum Outcome { WIN, LOSS }

	public enum Source { USER, AGENT }

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

	/** Optional, user-reported at Take time (Story 11.1, F22). Never set for a Declined decision.
	 * Null on every row written before this story — the journal must render that as "not recorded",
	 * not zero. */
	@Column(name = "entry_price")
	private BigDecimal entryPrice;

	@Column(name = "position_size")
	private BigDecimal positionSize;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private Source source;

	protected TradeDecision() {
		// JPA
	}

	public TradeDecision(Long recommendationId, Decision decision, String reasoning, String snapshot,
			BigDecimal entryPrice, BigDecimal positionSize, Source source) {
		this.recommendationId = recommendationId;
		this.decision = decision;
		this.reasoning = reasoning;
		this.snapshot = snapshot;
		this.entryPrice = entryPrice;
		this.positionSize = positionSize;
		this.source = source;
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

	public Instant getDecidedAt() {
		return decidedAt;
	}

	public Outcome getOutcome() {
		return outcome;
	}

	public BigDecimal getEntryPrice() {
		return entryPrice;
	}

	public BigDecimal getPositionSize() {
		return positionSize;
	}

	public Source getSource() {
		return source;
	}
}
