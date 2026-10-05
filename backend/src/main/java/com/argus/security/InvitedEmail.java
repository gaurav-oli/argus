package com.argus.security;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Locale;

/** One email the admin has allowed to sign in with Google. Lowercased for case-insensitive lookup. */
@Entity
@Table(name = "invited_email")
public class InvitedEmail {

	@Id
	private String email;

	@Column(name = "invited_at", nullable = false)
	private Instant invitedAt = Instant.now();

	@Column(name = "invited_by")
	private String invitedBy;

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

	public String getEmail() { return email; }
	public Instant getInvitedAt() { return invitedAt; }
	public String getInvitedBy() { return invitedBy; }
}
