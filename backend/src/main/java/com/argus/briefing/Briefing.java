package com.argus.briefing;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * A generated Morning Briefing (Epic 8, FR-16): a short {@code headline} (also used as the push
 * message) and a longer {@code body} narrative. One row per generation; the UI and the morning push
 * read the most recent by {@code generatedAt}.
 */
@Entity
@Table(name = "briefings")
public class Briefing {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

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
