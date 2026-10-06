package com.argus.security;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** One Google-authenticated person using Argus. {@code admin} is never settable via the API —
 * it is granted once, on first login, only to the email configured as {@code ARGUS_ADMIN_EMAIL}. */
@Entity
@Table(name = "app_user")
public class AppUser {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "google_sub", nullable = false, unique = true)
	private String googleSub;

	@Column(nullable = false, unique = true)
	private String email;

	@Column(nullable = false)
	private String name;

	@Column(name = "picture_url")
	private String pictureUrl;

	@Column(name = "is_admin", nullable = false)
	private boolean admin;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt = Instant.now();

	@Column(name = "last_login_at")
	private Instant lastLoginAt;

	@Column(name = "login_count", nullable = false)
	private int loginCount;

	/** Non-null while the admin has revoked this person's access; their data is kept. */
	@Column(name = "revoked_at")
	private Instant revokedAt;

	protected AppUser() {
		// JPA
	}

	public AppUser(String googleSub, String email, String name, String pictureUrl, boolean admin) {
		this.googleSub = googleSub;
		this.email = email;
		this.name = name;
		this.pictureUrl = pictureUrl;
		this.admin = admin;
	}

	/** Google's profile fields can change (a new photo, a display-name change) — keep them current. */
	public void refreshProfile(String name, String pictureUrl) {
		if (name != null && !name.isBlank()) {
			this.name = name;
		}
		this.pictureUrl = pictureUrl;
	}

	public void recordLogin() {
		this.lastLoginAt = Instant.now();
		this.loginCount++;
	}

	public void revoke() {
		if (revokedAt == null) {
			revokedAt = Instant.now();
		}
	}

	public void restore() {
		revokedAt = null;
	}

	public boolean isRevoked() { return revokedAt != null; }
	public Instant getRevokedAt() { return revokedAt; }

	public Long getId() { return id; }
	public String getGoogleSub() { return googleSub; }
	public String getEmail() { return email; }
	public String getName() { return name; }
	public String getPictureUrl() { return pictureUrl; }
	public boolean isAdmin() { return admin; }
	public Instant getCreatedAt() { return createdAt; }
	public Instant getLastLoginAt() { return lastLoginAt; }
	public int getLoginCount() { return loginCount; }
}
