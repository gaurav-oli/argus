package com.argus.security;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Locale;

/** One email the admin has allowed to sign in with Google. Lowercased for case-insensitive lookup.
 * {@code token} identifies this person's own invite link (for open-tracking); {@code emailSentAt}/
 * {@code openedAt} are null until the admin actually sends the email / the person visits the link —
 * whether they've actually joined is {@code app_user}, not tracked here. */
@Entity
@Table(name = "invited_email")
public class InvitedEmail {

	@Id
	private String email;

	@Column(name = "invited_at", nullable = false)
	private Instant invitedAt = Instant.now();

	@Column(name = "invited_by")
	private String invitedBy;

	@Column(name = "token", unique = true)
	private String token;

	@Column(name = "email_sent_at")
	private Instant emailSentAt;

	@Column(name = "opened_at")
	private Instant openedAt;

	protected InvitedEmail() {
		// JPA
	}

	public InvitedEmail(String email, String invitedBy) {
		this.email = normalize(email);
		this.invitedBy = invitedBy;
	}

	public static String normalize(String email) {
		return email == null ? null : email.trim().toLowerCase(Locale.ROOT);
	}

	/** This person's own invite-link token, creating one on first call (idempotent after that). */
	public String ensureToken() {
		if (token == null) {
			byte[] bytes = new byte[32];
			new java.security.SecureRandom().nextBytes(bytes);
			token = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
		}
		return token;
	}

	public void markEmailSent() {
		this.emailSentAt = Instant.now();
	}

	/** First visit only — never overwrites an earlier open time. */
	public void markOpened() {
		if (this.openedAt == null) {
			this.openedAt = Instant.now();
		}
	}

	public String getEmail() { return email; }
	public Instant getInvitedAt() { return invitedAt; }
	public String getInvitedBy() { return invitedBy; }
	public String getToken() { return token; }
	public Instant getEmailSentAt() { return emailSentAt; }
	public Instant getOpenedAt() { return openedAt; }
}
