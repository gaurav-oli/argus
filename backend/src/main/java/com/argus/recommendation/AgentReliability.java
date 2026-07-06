package com.argus.recommendation;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * One agent's realized reliability (Phase B, FR-11 follow-up): how often its directional signal agreed
 * with what the Investor's closed paper trades actually did, and the resulting clamped weight
 * multiplier. Recomputed by {@link AdaptiveTuningService}; read when gathering signals.
 */
@Entity
@Table(name = "agent_reliability")
public class AgentReliability {

	@Id
	private String agent;

	@Column(name = "sample_size", nullable = false)
	private int sampleSize;

	@Column(name = "hit_rate")
	private BigDecimal hitRate;

	@Column(name = "weight_multiplier", nullable = false)
	private BigDecimal weightMultiplier = BigDecimal.ONE;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt = Instant.now();

	protected AgentReliability() {
		// JPA
	}

	public AgentReliability(String agent) {
		this.agent = agent;
	}

	void update(int sampleSize, Double hitRate, double weightMultiplier) {
		this.sampleSize = sampleSize;
		this.hitRate = hitRate == null ? null : BigDecimal.valueOf(hitRate);
		this.weightMultiplier = BigDecimal.valueOf(weightMultiplier);
		this.updatedAt = Instant.now();
	}

	public String getAgent() {
		return agent;
	}

	public int getSampleSize() {
		return sampleSize;
	}

	public BigDecimal getHitRate() {
		return hitRate;
	}

	public BigDecimal getWeightMultiplier() {
		return weightMultiplier;
	}

	public Instant getUpdatedAt() {
		return updatedAt;
	}
}
