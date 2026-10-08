package com.argus.recommendation;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** A recorded recommendation outcome the graduation state machine evaluates (Story 6.6). */
@Entity
@Table(name = "paper_trades")
public class PaperTrade {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false)
	private boolean won;

	@Column(name = "recommendation_id")
	private Long recommendationId;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt = Instant.now();

	/** False for an outcome of a trade the old (pre-2026-09-25) system opened: recorded, but never used to
	 * promote, demote or freeze the current Agent 5 (see GraduationService). */
	@Column(name = "counts_for_graduation", nullable = false)
	private boolean countsForGraduation = true;

	protected PaperTrade() {
		// JPA
	}

	public PaperTrade(boolean won, Long recommendationId) {
		this(won, recommendationId, true);
	}

	public PaperTrade(boolean won, Long recommendationId, boolean countsForGraduation) {
		this.won = won;
		this.recommendationId = recommendationId;
		this.countsForGraduation = countsForGraduation;
	}

	public boolean isWon() {
		return won;
	}

	public Long getRecommendationId() {
		return recommendationId;
	}
}
