package com.argus.social;

import com.argus.intelligence.SentimentLabel;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * One crowd post mentioning a held ticker (Agent 2 — Social Media Intelligence). Sourced from
 * StockTwits or Reddit; {@code sentimentLabel} is the crowd's read (StockTwits' own tag when
 * present, else a lightweight classifier). Dedup key is {@code (source, externalId)}.
 */
@Entity
@Table(name = "social_posts")
public class SocialPost {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false)
	private String ticker;

	@Column(nullable = false)
	private String source;

	@Column(name = "external_id", nullable = false)
	private String externalId;

	private String author;

	@Column(columnDefinition = "text")
	private String body;

	private String url;

	@Enumerated(EnumType.STRING)
	@Column(name = "sentiment_label")
	private SentimentLabel sentimentLabel;

	@Column(name = "sentiment_score")
	private BigDecimal sentimentScore;

	@Column(name = "posted_at")
	private Instant postedAt;

	@Column(name = "ingested_at", nullable = false)
	private Instant ingestedAt = Instant.now();

	protected SocialPost() {
		// JPA
	}

	public SocialPost(String ticker, String source, String externalId, String author, String body,
			String url, SentimentLabel sentimentLabel, BigDecimal sentimentScore, Instant postedAt) {
		this.ticker = ticker;
		this.source = source;
		this.externalId = externalId;
		this.author = author;
		this.body = body;
		this.url = url;
		this.sentimentLabel = sentimentLabel;
		this.sentimentScore = sentimentScore;
		this.postedAt = postedAt;
	}

	public Long getId() {
		return id;
	}

	public String getTicker() {
		return ticker;
	}

	public String getSource() {
		return source;
	}

	public String getAuthor() {
		return author;
	}

	public String getBody() {
		return body;
	}

	public String getUrl() {
		return url;
	}

	public SentimentLabel getSentimentLabel() {
		return sentimentLabel;
	}

	public Instant getPostedAt() {
		return postedAt;
	}
}
