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
 * A single on-demand bull-vs-bear researcher debate on a {@link Recommendation} (TradingAgents-style
 * Researcher Team, adapted to a single combined-JSON call — see {@link RecommendationDebateService}
 * javadoc). User-triggered only ("Debate this call"), never wired into the automatic pipeline. Every
 * run is kept, not upserted, so re-running as new information lands builds a small history.
 */
@Entity
@Table(name = "recommendation_debates")
public class RecommendationDebate {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "recommendation_id", nullable = false)
	private Long recommendationId;

	@Column(name = "bull_case", columnDefinition = "text", nullable = false)
	private String bullCase;

	@Column(name = "bear_case", columnDefinition = "text", nullable = false)
	private String bearCase;

	@Column(columnDefinition = "text", nullable = false)
	private String synthesis;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private DebateVerdict verdict;

	@Column(nullable = false)
	private String model;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt = Instant.now();

	protected RecommendationDebate() {
		// JPA
	}

	public RecommendationDebate(Long recommendationId, String bullCase, String bearCase, String synthesis,
			DebateVerdict verdict, String model) {
		this.recommendationId = recommendationId;
		this.bullCase = bullCase;
		this.bearCase = bearCase;
		this.synthesis = synthesis;
		this.verdict = verdict;
		this.model = model;
	}

	public Long getId() {
		return id;
	}

	public Long getRecommendationId() {
		return recommendationId;
	}

	public String getBullCase() {
		return bullCase;
	}

	public String getBearCase() {
		return bearCase;
	}

	public String getSynthesis() {
		return synthesis;
	}

	public DebateVerdict getVerdict() {
		return verdict;
	}

	public String getModel() {
		return model;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}
}
