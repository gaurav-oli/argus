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
 *
 * <p>{@code userId} (Phase 2, multi-user) is which signed-in person registered this device — not a
 * Hibernate {@code @TenantId} like the portfolio entities, since the one thing that reads across
 * everyone's devices ({@code sendToAll}, shared market alerts) is meant to, by design. It is what
 * lets {@code sendToUser} target one person's own briefing push without touching anyone else's phone.
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

	/** Null for a subscription registered before multi-user (pre-Phase-2) and never re-subscribed. */
	@Column(name = "user_id")
	private Long userId;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt = Instant.now();

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt = Instant.now();

	protected PushSubscription() {
		// JPA
	}

	public PushSubscription(String endpoint, String p256dh, String auth, Long userId) {
		this.endpoint = endpoint;
		this.p256dh = p256dh;
		this.auth = auth;
		this.userId = userId;
	}

	/** Update the client keys for an existing device (re-subscribe) — also retargets {@code userId},
	 * since the same browser/device can later be used to sign in as someone else. */
	public void refresh(String p256dh, String auth, Long userId) {
		this.p256dh = p256dh;
		this.auth = auth;
		this.userId = userId;
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

	public Long getUserId() {
		return userId;
	}
}
