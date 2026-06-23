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

/**
 * One agent's contribution to a {@link Recommendation} (Story 6.2) — the persisted diagnostic row.
 * Conflicting signals are stored alongside agreeing ones; nothing is hidden.
 */
@Entity
@Table(name = "recommendation_signals")
public class RecommendationSignal {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false)
	private String agent;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private SignalDirection direction;

	@Column(nullable = false)
	private BigDecimal weight;

	@Column(name = "signed_weight", nullable = false)
	private BigDecimal signedWeight;

	@Column(columnDefinition = "text")
	private String rationale;

	protected RecommendationSignal() {
		// JPA
	}

	public RecommendationSignal(AgentSignal signal) {
		this.agent = signal.agent();
		this.direction = signal.direction();
		this.weight = BigDecimal.valueOf(signal.weight());
		this.signedWeight = BigDecimal.valueOf(signal.direction().sign() * signal.weight());
		this.rationale = signal.rationale();
	}

	public Long getId() {
		return id;
	}

	public String getAgent() {
		return agent;
	}

	public SignalDirection getDirection() {
		return direction;
	}

	public BigDecimal getWeight() {
		return weight;
	}

	public BigDecimal getSignedWeight() {
		return signedWeight;
	}

	public String getRationale() {
		return rationale;
	}
}
