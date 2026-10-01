package com.argus.deepanalysis;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * One persisted reading of {@link DeepScorecard}'s live computation, for one (verdict, horizon) cell — so
 * Agent 11's track record against the S&amp;P 500 survives a restart and can be charted over time, rather
 * than only existing in {@link DeepScorecardService}'s 10-minute in-memory cache. Append-only: a snapshot
 * run writes one fresh row per cell rather than overwriting the last one, so the history is real.
 */
@Entity
@Table(name = "deep_scorecard_snapshot")
public class DeepScorecardSnapshot {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false)
	private String verdict;

	@Column(name = "horizon_days", nullable = false)
	private int horizonDays;

	@Column(nullable = false)
	private int observations;

	@Column(name = "mean_excess_pct", nullable = false)
	private BigDecimal meanExcessPct;

	/** Null for WAIT, which makes no directional claim and so has no hit rate. */
	@Column(name = "hit_rate")
	private BigDecimal hitRate;

	@Column(name = "total_verdicts", nullable = false)
	private int totalVerdicts;

	@Column(name = "computed_at", nullable = false)
	private Instant computedAt = Instant.now();

	protected DeepScorecardSnapshot() {
	}

	public DeepScorecardSnapshot(DeepVerdict verdict, int horizonDays, DeepScorecard.Cell cell, int totalVerdicts) {
		this.verdict = verdict.name();
		this.horizonDays = horizonDays;
		this.observations = cell.n();
		this.meanExcessPct = BigDecimal.valueOf(cell.meanExcessPct());
		this.hitRate = cell.hitRate() == null ? null : BigDecimal.valueOf(cell.hitRate());
		this.totalVerdicts = totalVerdicts;
	}

	public Long getId() { return id; }
	public DeepVerdict getVerdict() { return DeepVerdict.valueOf(verdict); }
	public int getHorizonDays() { return horizonDays; }
	public int getObservations() { return observations; }
	public BigDecimal getMeanExcessPct() { return meanExcessPct; }
	public BigDecimal getHitRate() { return hitRate; }
	public int getTotalVerdicts() { return totalVerdicts; }
	public Instant getComputedAt() { return computedAt; }
}
