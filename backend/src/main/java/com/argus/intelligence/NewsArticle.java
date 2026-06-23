package com.argus.intelligence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * A news article ingested by Agent 1 (Story 4.1, FR-8). Deduplicated on {@code (source, externalId)}
 * — the natural key from the upstream feed — so an overlapping ingestion window never double-stores.
 * {@code tickers} are the relevance tags (held symbols the article mentions); sentiment is scored
 * later (Story 4.2) and is intentionally absent here.
 */
@Entity
@Table(name = "news_articles")
public class NewsArticle {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false)
	private String source;

	@Column(name = "external_id", nullable = false)
	private String externalId;

	private String url;

	@Column(nullable = false)
	private String headline;

	@Column(columnDefinition = "text")
	private String summary;

	@Column(name = "published_at", nullable = false)
	private Instant publishedAt;

	@JdbcTypeCode(SqlTypes.ARRAY)
	@Column(columnDefinition = "text[]", nullable = false)
	private String[] tickers = new String[0];

	@Column(name = "ingested_at", nullable = false)
	private Instant ingestedAt = Instant.now();

	// Sentiment stage (Story 4.2) — null until the sentiment agent processes the article.
	@Enumerated(EnumType.STRING)
	@Column(name = "sentiment_label")
	private SentimentLabel sentimentLabel;

	@Column(name = "sentiment_score")
	private BigDecimal sentimentScore;

	@Column(name = "relevance_score")
	private BigDecimal relevanceScore;

	@Column(name = "analyzed_at")
	private Instant analyzedAt;

	protected NewsArticle() {
		// JPA
	}

	public NewsArticle(String source, String externalId, String url, String headline, String summary,
			Instant publishedAt, String[] tickers) {
		this.source = source;
		this.externalId = externalId;
		this.url = url;
		this.headline = headline;
		this.summary = summary;
		this.publishedAt = publishedAt;
		this.tickers = (tickers == null) ? new String[0] : tickers;
	}

	public Long getId() {
		return id;
	}

	public String getSource() {
		return source;
	}

	public String getExternalId() {
		return externalId;
	}

	public String getUrl() {
		return url;
	}

	public String getHeadline() {
		return headline;
	}

	public String getSummary() {
		return summary;
	}

	public Instant getPublishedAt() {
		return publishedAt;
	}

	public String[] getTickers() {
		return tickers;
	}

	public Instant getIngestedAt() {
		return ingestedAt;
	}

	public SentimentLabel getSentimentLabel() {
		return sentimentLabel;
	}

	public BigDecimal getSentimentScore() {
		return sentimentScore;
	}

	public BigDecimal getRelevanceScore() {
		return relevanceScore;
	}

	public Instant getAnalyzedAt() {
		return analyzedAt;
	}

	/** True once the sentiment stage has scored this article (Story 4.2) — used to skip redeliveries. */
	public boolean isAnalyzed() {
		return analyzedAt != null;
	}

	/** Persist the small-model assessment; scores are stored at the column's 3-decimal scale. */
	public void applySentiment(SentimentAnalysis analysis, Instant at) {
		this.sentimentLabel = analysis.label();
		this.sentimentScore = scaled(analysis.score());
		this.relevanceScore = scaled(analysis.relevance());
		this.analyzedAt = at;
	}

	private static BigDecimal scaled(double v) {
		return BigDecimal.valueOf(v).setScale(3, RoundingMode.HALF_UP);
	}
}
