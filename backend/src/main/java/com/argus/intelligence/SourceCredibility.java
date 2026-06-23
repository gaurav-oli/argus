package com.argus.intelligence;

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
 * Credibility record for a single news source (Story 4.3, FR-9). Holds the 0–100 {@code score}, its
 * derived {@link CredibilityTier}, the auto-block flag, and correct/incorrect signal counts. All
 * mutation goes through the methods here so score, tier, and blocked stay consistent.
 */
@Entity
@Table(name = "source_credibility")
public class SourceCredibility {

	/** A source not yet seen starts here (Bronze) — neutral-but-cautious. */
	public static final int UNKNOWN_START = 35;
	/** Below this score the source is auto-blocked from feeding recommendations. */
	public static final int BLOCK_THRESHOLD = 10;
	static final int CORRECT_DELTA = 2;
	static final int INCORRECT_DELTA = -3;
	private static final int MIN = 0;
	private static final int MAX = 100;

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false, unique = true)
	private String source;

	@Column(nullable = false)
	private int score;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private CredibilityTier tier;

	@Column(nullable = false)
	private boolean blocked;

	@Column(name = "correct_count", nullable = false)
	private int correctCount;

	@Column(name = "incorrect_count", nullable = false)
	private int incorrectCount;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt = Instant.now();

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt = Instant.now();

	protected SourceCredibility() {
		// JPA
	}

	private SourceCredibility(String source, int score) {
		this.source = source;
		setScore(score);
	}

	/** A freshly seen source at the {@link #UNKNOWN_START} baseline. */
	public static SourceCredibility unknown(String source) {
		return new SourceCredibility(source, UNKNOWN_START);
	}

	/**
	 * Apply a signal outcome: +2 if the source's signal was correct, −3 if incorrect (clamped
	 * 0–100). Returns {@code true} iff this call transitioned the source into the blocked state
	 * (so the caller notifies exactly once).
	 */
	public boolean recordOutcome(boolean correct) {
		boolean wasBlocked = this.blocked;
		if (correct) {
			this.correctCount++;
		} else {
			this.incorrectCount++;
		}
		setScore(this.score + (correct ? CORRECT_DELTA : INCORRECT_DELTA));
		return this.blocked && !wasBlocked;
	}

	private void setScore(int raw) {
		this.score = Math.max(MIN, Math.min(MAX, raw));
		this.tier = CredibilityTier.forScore(this.score);
		this.blocked = this.score < BLOCK_THRESHOLD;
		this.updatedAt = Instant.now();
	}

	public Long getId() {
		return id;
	}

	public String getSource() {
		return source;
	}

	public int getScore() {
		return score;
	}

	public CredibilityTier getTier() {
		return tier;
	}

	public boolean isBlocked() {
		return blocked;
	}

	public int getCorrectCount() {
		return correctCount;
	}

	public int getIncorrectCount() {
		return incorrectCount;
	}
}
