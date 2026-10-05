package com.argus.portfolio;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.TenantId;
import org.hibernate.type.SqlTypes;

/** One daily Portfolio Health Score point (Story 3.8). {@code breakdown} is JSON of the deductions.
 * One row per {@code scoredOn} PER PERSON (Phase 2, multi-user) — enforced by the composite
 * {@code (user_id, scored_on)} DB constraint, not this column alone. */
@Entity
@Table(name = "health_score")
public class HealthScore {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@TenantId
	@Column(name = "user_id", nullable = false, updatable = false)
	private Long userId;

	@Column(name = "scored_on", nullable = false)
	private LocalDate scoredOn;

	@Column(nullable = false)
	private int score;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(nullable = false)
	private String breakdown;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt = Instant.now();

	protected HealthScore() {
		// JPA
	}

	public HealthScore(LocalDate scoredOn, int score, String breakdown) {
		this.scoredOn = scoredOn;
		this.score = score;
		this.breakdown = breakdown;
	}

	public void update(int score, String breakdown) {
		this.score = score;
		this.breakdown = breakdown;
	}

	public Long getId() {
		return id;
	}

	public Long getUserId() {
		return userId;
	}

	public LocalDate getScoredOn() {
		return scoredOn;
	}

	public int getScore() {
		return score;
	}

	public String getBreakdown() {
		return breakdown;
	}
}
