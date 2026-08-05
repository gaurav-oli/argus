package com.argus.intelligence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * One article the LLM classification pass ({@link SentimentAnalyzer}'s {@code macro} field) flagged
 * as macro/political news that {@link MacroRelevanceTagger}'s keyword list hadn't already caught — the
 * raw evidence {@link MacroKeywordLearningService}'s weekly review learns new keywords from.
 */
@Entity
@Table(name = "macro_keyword_miss")
public class MacroKeywordMiss {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "article_id", nullable = false)
	private Long articleId;

	@Column(nullable = false)
	private String headline;

	private String summary;

	@Column(name = "detected_at", nullable = false)
	private Instant detectedAt = Instant.now();

	@Column(nullable = false)
	private boolean reviewed;

	protected MacroKeywordMiss() {
		// JPA
	}

	public MacroKeywordMiss(Long articleId, String headline, String summary) {
		this.articleId = articleId;
		this.headline = headline;
		this.summary = summary;
	}

	public Long getId() {
		return id;
	}

	public Long getArticleId() {
		return articleId;
	}

	public String getHeadline() {
		return headline;
	}

	public String getSummary() {
		return summary;
	}

	public Instant getDetectedAt() {
		return detectedAt;
	}

	public boolean isReviewed() {
		return reviewed;
	}

	public void markReviewed() {
		this.reviewed = true;
	}
}
