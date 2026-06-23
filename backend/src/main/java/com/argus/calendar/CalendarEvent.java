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
import java.time.LocalDate;

/**
 * An upcoming calendar event ingested by Agent 7 (Story 5.1, FR-21). Deduplicated on
 * {@code (source, externalId)}. {@code ticker} is null for macro events (Fed/CPI/jobs/GDP).
 */
@Entity
@Table(name = "calendar_events")
public class CalendarEvent {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private CalendarEventType type;

	private String ticker;

	@Column(nullable = false)
	private String title;

	@Column(name = "event_date", nullable = false)
	private LocalDate eventDate;

	@Column(nullable = false)
	private String source;

	@Column(name = "external_id", nullable = false)
	private String externalId;

	@Column(name = "ingested_at", nullable = false)
	private Instant ingestedAt = Instant.now();

	protected CalendarEvent() {
		// JPA
	}

	public CalendarEvent(CalendarEventType type, String ticker, String title, LocalDate eventDate,
			String source, String externalId) {
		this.type = type;
		this.ticker = ticker;
		this.title = title;
		this.eventDate = eventDate;
		this.source = source;
		this.externalId = externalId;
	}

	public Long getId() {
		return id;
	}

	public CalendarEventType getType() {
		return type;
	}

	public String getTicker() {
		return ticker;
	}

	public String getTitle() {
		return title;
	}

	public LocalDate getEventDate() {
		return eventDate;
	}

	public String getSource() {
		return source;
	}

	public String getExternalId() {
		return externalId;
	}
}
