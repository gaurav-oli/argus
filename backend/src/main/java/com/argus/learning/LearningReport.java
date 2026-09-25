package com.argus.learning;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;

/** One run of the Trade Learner: what it looked at and its plain-English reading of what wins and loses. */
@Entity
@Table(name = "learning_report")
public class LearningReport {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt = Instant.now();

	@Column(name = "trades_analyzed", nullable = false)
	private int tradesAnalyzed;

	@Column(nullable = false)
	private int clusters;

	@Column(name = "baseline_win")
	private BigDecimal baselineWin;

	@Column(name = "baseline_excess")
	private BigDecimal baselineExcess;

	@Column(name = "losses_summary")
	private String lossesSummary;

	@Column(name = "wins_summary")
	private String winsSummary;

	private String narrative;
	private String model;

	protected LearningReport() {
		// JPA
	}

	public LearningReport(int tradesAnalyzed, int clusters, double baselineWin, double baselineExcess, String lossesSummary,
			String winsSummary, String narrative, String model) {
		this.tradesAnalyzed = tradesAnalyzed;
		this.clusters = clusters;
		this.baselineWin = BigDecimal.valueOf(baselineWin).setScale(4, RoundingMode.HALF_UP);
		this.baselineExcess = BigDecimal.valueOf(baselineExcess).setScale(3, RoundingMode.HALF_UP);
		this.lossesSummary = lossesSummary;
		this.winsSummary = winsSummary;
		this.narrative = narrative;
		this.model = model;
	}

	public Long getId() { return id; }
	public Instant getCreatedAt() { return createdAt; }
	public int getTradesAnalyzed() { return tradesAnalyzed; }
	public int getClusters() { return clusters; }
	public BigDecimal getBaselineWin() { return baselineWin; }
	public BigDecimal getBaselineExcess() { return baselineExcess; }
	public String getLossesSummary() { return lossesSummary; }
	public String getWinsSummary() { return winsSummary; }
	public String getNarrative() { return narrative; }
	public String getModel() { return model; }
}
