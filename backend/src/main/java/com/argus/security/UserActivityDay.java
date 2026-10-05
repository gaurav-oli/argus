package com.argus.security;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;

/**
 * One (user, day) row recording the first and last authenticated request seen that day — the
 * honest, approximate "time spent" signal for the admin's usage-stats view. It measures a span of
 * activity, not continuous attention; {@link UserActivityService} is the only writer.
 */
@Entity
@Table(name = "user_activity_day")
public class UserActivityDay {

	@EmbeddedId
	private Key id;

	@Column(name = "first_seen_at", nullable = false)
	private Instant firstSeenAt;

	@Column(name = "last_seen_at", nullable = false)
	private Instant lastSeenAt;

	protected UserActivityDay() {
		// JPA
	}

	public UserActivityDay(Long userId, LocalDate date, Instant now) {
		this.id = new Key(userId, date);
		this.firstSeenAt = now;
		this.lastSeenAt = now;
	}

	public void touch(Instant now) {
		this.lastSeenAt = now;
	}

	public Key getId() { return id; }
	public Instant getFirstSeenAt() { return firstSeenAt; }
	public Instant getLastSeenAt() { return lastSeenAt; }

	@Embeddable
	public static class Key implements Serializable {

		@Column(name = "user_id")
		private Long userId;

		@Column(name = "activity_date")
		private LocalDate activityDate;

		protected Key() {
			// JPA
		}

		public Key(Long userId, LocalDate activityDate) {
			this.userId = userId;
			this.activityDate = activityDate;
		}

		public Long getUserId() { return userId; }
		public LocalDate getActivityDate() { return activityDate; }

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (!(o instanceof Key key)) return false;
			return Objects.equals(userId, key.userId) && Objects.equals(activityDate, key.activityDate);
		}

		@Override
		public int hashCode() {
			return Objects.hash(userId, activityDate);
		}
	}
}
