package com.argus.notification;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** Single-row notification preferences (per-type toggles, quiet hours, muted tickers). */
@Entity
@Table(name = "notification_prefs")
public class NotificationPrefs {

	static final short SINGLETON_ID = 1;

	@Id
	private Short id = SINGLETON_ID;

	@Column(name = "briefing_enabled", nullable = false)
	private boolean briefingEnabled = true;

	@Column(name = "breaking_enabled", nullable = false)
	private boolean breakingEnabled = true;

	@Column(name = "alerts_enabled", nullable = false)
	private boolean alertsEnabled = true;

	@Column(name = "quiet_start_hour")
	private Short quietStartHour;

	@Column(name = "quiet_end_hour")
	private Short quietEndHour;

	@Column(name = "muted_tickers", columnDefinition = "text[]", nullable = false)
	private String[] mutedTickers = new String[0];

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt = Instant.now();

	public boolean isBriefingEnabled() {
		return briefingEnabled;
	}

	public boolean isBreakingEnabled() {
		return breakingEnabled;
	}

	public boolean isAlertsEnabled() {
		return alertsEnabled;
	}

	public Short getQuietStartHour() {
		return quietStartHour;
	}

	public Short getQuietEndHour() {
		return quietEndHour;
	}

	public String[] getMutedTickers() {
		return mutedTickers;
	}

	void update(boolean briefing, boolean breaking, boolean alerts, Short quietStart, Short quietEnd,
			String[] muted) {
		this.id = SINGLETON_ID;
		this.briefingEnabled = briefing;
		this.breakingEnabled = breaking;
		this.alertsEnabled = alerts;
		this.quietStartHour = quietStart;
		this.quietEndHour = quietEnd;
		this.mutedTickers = muted == null ? new String[0] : muted;
		this.updatedAt = Instant.now();
	}
}
