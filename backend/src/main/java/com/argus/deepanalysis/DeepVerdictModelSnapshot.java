package com.argus.deepanalysis;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;

/**
 * One saved reading of how Agent 11's verdicts have done, split by which model actually produced them — "HAIKU"
 * (the paid Claude Haiku escalation) or "LOCAL" (the free Gemma fallback) — at one (verdict, horizon) cell.
 * Append-only, same shape and purpose as {@link DeepScorecardSnapshot} but segmented by model, so "is paying
 * for Haiku's verdict actually better than Gemma alone?" has a real, measured answer instead of just reasoning
 * about the architecture.
 */
@Entity
@Table(name = "deep_verdict_model_snapshot")
public class DeepVerdictModelSnapshot {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false)
	private String model;

	@Column(nullable = false)
	private String verdict;

	@Column(name = "horizon_days", nullable = false)
	private int horizonDays;

	@Column(nullable = false)
	private int observations;

	@Column(name = "mean_excess_pct", nullable = false)
	private BigDecimal meanExcessPct;

	@Column(name = "hit_rate")
	private BigDecimal hitRate;

	@Column(name = "computed_at", nullable = false)
	private Instant computedAt = Instant.now();

	protected DeepVerdictModelSnapshot() {
		// JPA
	}

	public DeepVerdictModelSnapshot(String model, DeepVerdict verdict, int horizonDays, DeepScorecard.Cell cell) {
		this.model = model;
		this.verdict = verdict.name();
		this.horizonDays = horizonDays;
		this.observations = cell.n();
		this.meanExcessPct = BigDecimal.valueOf(cell.meanExcessPct()).setScale(4, RoundingMode.HALF_UP);
		this.hitRate = cell.hitRate() == null ? null : BigDecimal.valueOf(cell.hitRate()).setScale(4, RoundingMode.HALF_UP);
	}

	public Long getId() { return id; }
	public String getModel() { return model; }
	public DeepVerdict getVerdict() { return DeepVerdict.valueOf(verdict); }
	public int getHorizonDays() { return horizonDays; }
	public int getObservations() { return observations; }
	public BigDecimal getMeanExcessPct() { return meanExcessPct; }
	public BigDecimal getHitRate() { return hitRate; }
	public Instant getComputedAt() { return computedAt; }
}
