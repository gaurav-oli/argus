package com.argus.notification;

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
 * A notification the tier routing deferred instead of pushing (Story 8.2 follow-up): NORMAL-tier
 * items wait for the next morning briefing, INFO-tier for the Sunday digest. Delivery marks
 * {@code deliveredAt}; rows are kept as the audit trail of what the quieter channels carried.
 */
@Entity
@Table(name = "deferred_notifications")
public class DeferredNotification {

	public enum Channel {
		BRIEFING, DIGEST
	}

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false)
	private String tier;

	@Column(nullable = false)
	private String title;

	private String body;

	private String url;

	private String ticker;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private Channel channel;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt = Instant.now();

	@Column(name = "delivered_at")
	private Instant deliveredAt;

	protected DeferredNotification() {
		// JPA
	}

	public DeferredNotification(String tier, String title, String body, String url, String ticker,
			Channel channel) {
		this.tier = tier;
		this.title = title;
		this.body = body;
		this.url = url;
		this.ticker = ticker;
		this.channel = channel;
	}

	public void markDelivered() {
		this.deliveredAt = Instant.now();
	}

	public Long getId() {
		return id;
	}

	public String getTier() {
		return tier;
	}

	public String getTitle() {
		return title;
	}

	public String getBody() {
		return body;
	}

	public String getUrl() {
		return url;
	}

	public String getTicker() {
		return ticker;
	}

	public Channel getChannel() {
		return channel;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public Instant getDeliveredAt() {
		return deliveredAt;
	}
}
