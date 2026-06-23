package com.argus.intelligence;

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
 * A non-held ("stranger") ticker under heavy news coverage (Story 4.4, FR-10). Carries the
 * pump-and-dump {@code riskScore} (0–100) and the elevated {@code requiredConsensus} (e.g. 6/7
 * agents) Agent 5 must clear before recommending it. One row per ticker, refreshed each cycle.
 */
@Entity
@Table(name = "stranger_alerts")
public class StrangerAlert {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false, unique = true)
	private String ticker;

	@Column(name = "coverage_count", nullable = false)
	private int coverageCount;

	@Column(name = "distinct_sources", nullable = false)
	private int distinctSources;

	@Column(name = "avg_source_score")
	private BigDecimal avgSourceScore;

	@Column(name = "risk_score", nullable = false)
	private int riskScore;

	@Column(name = "required_consensus", nullable = false)
	private int requiredConsensus;

	@Column(name = "window_start", nullable = false)
	private Instant windowStart;

	@Column(name = "detected_at", nullable = false)
	private Instant detectedAt = Instant.now();

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt = Instant.now();

	protected StrangerAlert() {
		// JPA
	}

	public StrangerAlert(String ticker, StrangerAssessment a, int requiredConsensus, Instant windowStart) {
		this.ticker = ticker;
		this.requiredConsensus = requiredConsensus;
		this.windowStart = windowStart;
		apply(a);
	}

	/** Refresh the assessment for an existing stranger (latest cycle wins). */
	public void apply(StrangerAssessment a) {
		this.coverageCount = a.coverageCount();
		this.distinctSources = a.distinctSources();
		this.avgSourceScore = BigDecimal.valueOf(a.averageSourceScore()).setScale(2, RoundingMode.HALF_UP);
		this.riskScore = a.riskScore();
		this.updatedAt = Instant.now();
	}

	public Long getId() {
		return id;
	}

	public String getTicker() {
		return ticker;
	}

	public int getCoverageCount() {
		return coverageCount;
	}

	public int getDistinctSources() {
		return distinctSources;
	}

	public BigDecimal getAvgSourceScore() {
		return avgSourceScore;
	}

	public int getRiskScore() {
		return riskScore;
	}

	public int getRequiredConsensus() {
		return requiredConsensus;
	}

	public Instant getWindowStart() {
		return windowStart;
	}
}
