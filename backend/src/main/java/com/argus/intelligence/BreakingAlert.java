package com.argus.intelligence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** A high-impact news alert that was pushed to the user's devices (immediate market-moving news). */
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

	@Column(name = "created_at", nullable = false)
	private Instant createdAt = Instant.now();

	protected BreakingAlert() {
		// JPA
	}

	BreakingAlert(String headline, String url, String[] tickers, String reason, double impact,
			String sentimentLabel) {
		this.headline = headline;
		this.url = url;
		this.tickers = tickers == null ? new String[0] : tickers;
		this.reason = reason;
		this.impact = impact;
		this.sentimentLabel = sentimentLabel;
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

	public Instant getCreatedAt() {
		return createdAt;
	}
}
