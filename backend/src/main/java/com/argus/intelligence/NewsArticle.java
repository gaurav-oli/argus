package com.argus.intelligence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
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
}
