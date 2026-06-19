package com.argus.agent;

import java.time.Instant;
import java.util.Map;

/**
 * The standard Argus inter-agent event envelope carried on every Redis Stream record.
 * All five fields are mandatory and populated by {@link AgentEventPublisher}.
 *
 * @param eventId    unique id (UUID)
 * @param type       dot-notation, past-tense event name (e.g. {@code news.detected})
 * @param occurredAt event time, UTC
 * @param version    envelope schema version
 * @param payload    event-specific data
 */
public record EventEnvelope(
		String eventId,
		String type,
		Instant occurredAt,
		int version,
		Map<String, Object> payload) {
}
