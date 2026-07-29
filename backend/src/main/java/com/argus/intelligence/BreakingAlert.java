package com.argus.intelligence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * A high-impact news alert that was pushed to the user's devices (immediate market-moving news).
 * Also the permanent in-app audit trail of what fired — {@code read} is a soft "Done Reading" flag,
 * never a delete, unlike the ephemeral {@link NewsCard} queue. {@code summary} is filled in later by
 * {@link BreakingAlertCurationService}, which also flags {@code duplicate} when Gemma judges a later
 * alert to be about the same underlying story as one already summarized — a duplicate is never shown
 * in the carousel but the row (and its push/audit record) is kept.
 */
@Entity
@Table(name = "breaking_alert")
public class BreakingAlert {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false)
	private String headline;

	private String url;

	@Column(columnDefinition = "text[]", nullable = false)
	private String[] tickers = new String[0];

	@Column(nullable = false)
	private String reason;

	@Column(nullable = false)
	private double impact;

	@Column(name = "sentiment_label")
	private String sentimentLabel;

	/** The originating article, for the curation pass to look up its snippet. Null on rows created
	 * before this column existed, or if the article was later deleted. */
	@Column(name = "article_id")
	private Long articleId;

	/** Gemma paragraph (what happened / why it matters / market impact + glossary); null while pending. */
	@Column(columnDefinition = "text")
	private String summary;

	@Column(name = "generated_at")
	private Instant generatedAt;

	/** True when the summary is the deterministic fallback (model call failed), not model-written. */
	@Column(nullable = false)
	private boolean fallback = false;

	/** True when Gemma judged this alert to be the same underlying story as an earlier, already
	 * summarized one. Duplicates are never surfaced in the carousel. */
	@Column(nullable = false)
	private boolean duplicate = false;

	/** "Done Reading" — soft dismiss so the audit trail survives; the carousel excludes read cards. */
	@Column(nullable = false)
	private boolean read = false;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt = Instant.now();

	protected BreakingAlert() {
		// JPA
	}

	BreakingAlert(String headline, String url, String[] tickers, String reason, double impact,
			String sentimentLabel, Long articleId) {
		this.headline = headline;
		this.url = url;
		this.tickers = tickers == null ? new String[0] : tickers;
		this.reason = reason;
		this.impact = impact;
		this.sentimentLabel = sentimentLabel;
		this.articleId = articleId;
	}

	/** Attach the generated paragraph; the card becomes ready to show in the carousel. */
	void summarize(String paragraph, boolean fallback) {
		this.summary = paragraph;
		this.fallback = fallback;
		this.generatedAt = Instant.now();
	}

	/** Flag as the same story as an earlier alert — never shown, but the row (and push record) stays. */
	void markDuplicate() {
		this.duplicate = true;
		this.generatedAt = Instant.now();
	}

	void markRead() {
		this.read = true;
	}

	public Long getId() {
		return id;
	}

	public String getHeadline() {
		return headline;
	}

	public String getUrl() {
		return url;
	}

	public String[] getTickers() {
		return tickers;
	}

	public String getReason() {
		return reason;
	}

	public double getImpact() {
		return impact;
	}

	public String getSentimentLabel() {
		return sentimentLabel;
	}

	public Long getArticleId() {
		return articleId;
	}

	public String getSummary() {
		return summary;
	}

	public Instant getGeneratedAt() {
		return generatedAt;
	}

	public boolean isFallback() {
		return fallback;
	}

	public boolean isDuplicate() {
		return duplicate;
	}

	public boolean isRead() {
		return read;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}
}
