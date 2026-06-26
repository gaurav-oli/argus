package com.argus.internet;

import com.argus.intelligence.SentimentLabel;
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
 * One unit of internet attention for a held ticker (Agent 3 — Internet Intelligence): a Hacker News
 * story (with points + keyword sentiment) or a Wikipedia daily pageview count (score = views,
 * sentiment null). Dedup key is {@code (source, externalId)}.
 */
@Entity
@Table(name = "web_mentions")
public class WebMention {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false)
	private String ticker;

	@Column(nullable = false)
	private String source;

	@Column(name = "external_id", nullable = false)
	private String externalId;

	private String title;

	private String url;

	@Column(nullable = false)
	private long score;

	@Enumerated(EnumType.STRING)
	@Column(name = "sentiment_label")
	private SentimentLabel sentimentLabel;

	@Column(name = "posted_at")
	private Instant postedAt;

	@Column(name = "ingested_at", nullable = false)
	private Instant ingestedAt = Instant.now();

	protected WebMention() {
		// JPA
	}

	public WebMention(String ticker, String source, String externalId, String title, String url, long score,
			SentimentLabel sentimentLabel, Instant postedAt) {
		this.ticker = ticker;
		this.source = source;
		this.externalId = externalId;
		this.title = title;
		this.url = url;
		this.score = score;
		this.sentimentLabel = sentimentLabel;
		this.postedAt = postedAt;
	}

	public String getTicker() {
		return ticker;
	}

	public String getSource() {
		return source;
	}

	public String getTitle() {
		return title;
	}

	public String getUrl() {
		return url;
	}

	public long getScore() {
		return score;
	}

	public SentimentLabel getSentimentLabel() {
		return sentimentLabel;
	}

	public Instant getPostedAt() {
		return postedAt;
	}
}
