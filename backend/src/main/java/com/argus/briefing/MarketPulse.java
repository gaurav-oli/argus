package com.argus.briefing;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * The latest on-demand "market pulse" (Epic 8, FR-16 follow-up): a short local-model summary of the
 * market-impacting news captured so far. A singleton row (always {@code id = 1}); a refresh overwrites
 * it in place. {@code latestArticleAt} is the newest {@code publishedAt} the summary covered, so the
 * next refresh can detect "nothing major since we last checked" without asking the model again.
 */
@Entity
@Table(name = "market_pulse")
public class MarketPulse {

	static final short SINGLETON_ID = 1;

	@Id
	private Short id = SINGLETON_ID;

	@Column(nullable = false)
	private String summary;

	@Column(name = "article_count", nullable = false)
	private int articleCount;

	@Column(name = "latest_article_at")
	private Instant latestArticleAt;

	@Column(name = "generated_at", nullable = false)
	private Instant generatedAt = Instant.now();

	protected MarketPulse() {
		// JPA
	}

	MarketPulse(String summary, int articleCount, Instant latestArticleAt) {
		this.id = SINGLETON_ID;
		update(summary, articleCount, latestArticleAt);
	}

	void update(String summary, int articleCount, Instant latestArticleAt) {
		this.summary = summary;
		this.articleCount = articleCount;
		this.latestArticleAt = latestArticleAt;
		this.generatedAt = Instant.now();
	}

	public String getSummary() {
		return summary;
	}

	public int getArticleCount() {
		return articleCount;
	}

	public Instant getLatestArticleAt() {
		return latestArticleAt;
	}

	public Instant getGeneratedAt() {
		return generatedAt;
	}
}
