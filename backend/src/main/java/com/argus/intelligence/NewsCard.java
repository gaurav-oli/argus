package com.argus.intelligence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * A curated "news to read" card (Dashboard news section). Promoted from a {@link NewsArticle} that
 * ranked as important and recent, it carries a local-model (Gemma) paragraph — what happened and its
 * likely market impact — written on a background drip. {@code summary} is {@code null} until that
 * paragraph exists, so the UI only surfaces ready cards. The dashboard shows one card at a time,
 * highest {@link #impactScore} first; "Done Reading" deletes the row and the next card appears.
 */
@Entity
@Table(name = "news_card")
public class NewsCard {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "article_id", nullable = false)
	private Long articleId;

	@Column(nullable = false)
	private String headline;

	/** Gemma paragraph (what happened + market impact); null while pending generation. */
	@Column(columnDefinition = "text")
	private String summary;

	@Column(nullable = false)
	private String source;

	private String url;

	@Column(columnDefinition = "text[]", nullable = false)
	private String[] tickers = new String[0];

	@Column(name = "impact_score", nullable = false)
	private double impactScore;

	/** When the news happened. */
	@Column(name = "published_at", nullable = false)
	private Instant publishedAt;

	/** When Argus ingested the article. */
	@Column(name = "fetched_at", nullable = false)
	private Instant fetchedAt;

	@Column(name = "generated_at")
	private Instant generatedAt;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt = Instant.now();

	protected NewsCard() {
		// JPA
	}

	/** Create a pending card (no summary yet) from a ranked article. */
	NewsCard(NewsArticle article, double impactScore) {
		this.articleId = article.getId();
		this.headline = article.getHeadline();
		this.source = article.getSource();
		this.url = article.getUrl();
		this.tickers = article.getTickers() == null ? new String[0] : article.getTickers();
		this.impactScore = impactScore;
		this.publishedAt = article.getPublishedAt();
		this.fetchedAt = article.getIngestedAt();
	}

	/** Attach the generated paragraph; the card becomes "ready" to show. */
	void summarize(String paragraph) {
		this.summary = paragraph;
		this.generatedAt = Instant.now();
	}

	boolean isReady() {
		return summary != null;
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

	public String getSource() {
		return source;
	}

	public String getUrl() {
		return url;
	}

	public String[] getTickers() {
		return tickers;
	}

	public double getImpactScore() {
		return impactScore;
	}

	public Instant getPublishedAt() {
		return publishedAt;
	}

	public Instant getFetchedAt() {
		return fetchedAt;
	}

	public Instant getGeneratedAt() {
		return generatedAt;
	}
}
