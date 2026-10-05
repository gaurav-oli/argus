package com.argus.briefing;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.TenantId;

/**
 * A generated Morning Briefing (Epic 8, FR-16): a short {@code headline} (also used as the push
 * message) and a longer {@code body} narrative. One row per generation; the UI and the morning push
 * read the most recent by {@code generatedAt}.
 *
 * <p>{@code userId} (Phase 2, multi-user): each person gets their own briefing, generated from their
 * own portfolio — {@code @TenantId} keeps one person's generated text (which can describe their real
 * dollar values in plain English) from ever being readable by anyone else.
 */
@Entity
@Table(name = "briefings")
public class Briefing {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@TenantId
	@Column(name = "user_id", nullable = false, updatable = false)
	private Long userId;

	@Column(nullable = false)
	private String headline;

	@Column(nullable = false)
	private String body;

	@Column(name = "generated_at", nullable = false)
	private Instant generatedAt = Instant.now();

	/** True when this briefing was built by the deterministic fallback (model call failed), not the model. */
	@Column(nullable = false)
	private boolean fallback = false;

	protected Briefing() {
		// JPA
	}

	public Briefing(String headline, String body, boolean fallback) {
		this.headline = headline;
		this.body = body;
		this.fallback = fallback;
	}

	public Long getId() {
		return id;
	}

	public Long getUserId() {
		return userId;
	}

	public String getHeadline() {
		return headline;
	}

	public String getBody() {
		return body;
	}

	public Instant getGeneratedAt() {
		return generatedAt;
	}

	public boolean isFallback() {
		return fallback;
	}
}
