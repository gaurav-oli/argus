package com.argus.push;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * A browser Web Push subscription (Epic 8, FR-17). One row per device, keyed by its push-service
 * {@code endpoint}; {@code p256dh} + {@code auth} are the client keys used to encrypt payloads
 * (RFC 8291). Re-subscribing the same endpoint refreshes its keys via {@link #refresh}.
 */
@Entity
@Table(name = "push_subscriptions")
public class PushSubscription {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false, unique = true)
	private String endpoint;

	@Column(nullable = false)
	private String p256dh;

	@Column(nullable = false)
	private String auth;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt = Instant.now();

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt = Instant.now();

	protected PushSubscription() {
		// JPA
	}

	public PushSubscription(String endpoint, String p256dh, String auth) {
		this.endpoint = endpoint;
		this.p256dh = p256dh;
		this.auth = auth;
	}

	/** Update the client keys for an existing device (re-subscribe). */
	public void refresh(String p256dh, String auth) {
		this.p256dh = p256dh;
		this.auth = auth;
		this.updatedAt = Instant.now();
	}

	public Long getId() {
		return id;
	}

	public String getEndpoint() {
		return endpoint;
	}

	public String getP256dh() {
		return p256dh;
	}

	public String getAuth() {
		return auth;
	}
}
