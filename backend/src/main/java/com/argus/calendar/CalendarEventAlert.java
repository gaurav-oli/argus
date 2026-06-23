package com.argus.calendar;

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
 * A record that a lead-time alert has fired for a {@link CalendarEvent} at a given day-threshold
 * (Story 5.2). Its {@code (eventId, leadDays)} uniqueness is the idempotency guard that stops the
 * scanner re-queuing the same reminder.
 */
@Entity
@Table(name = "calendar_event_alerts")
public class CalendarEventAlert {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "event_id", nullable = false)
	private Long eventId;

	@Column(name = "lead_days", nullable = false)
	private int leadDays;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private AlertUrgency urgency;

	@Column(name = "fired_at", nullable = false)
	private Instant firedAt = Instant.now();

	protected CalendarEventAlert() {
		// JPA
	}

	public CalendarEventAlert(Long eventId, int leadDays, AlertUrgency urgency) {
		this.eventId = eventId;
		this.leadDays = leadDays;
		this.urgency = urgency;
	}

	public Long getId() {
		return id;
	}

	public Long getEventId() {
		return eventId;
	}

	public int getLeadDays() {
		return leadDays;
	}

	public AlertUrgency getUrgency() {
		return urgency;
	}
}
