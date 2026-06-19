package com.argus.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Converts an {@link EventEnvelope} to/from the flat {@code Map<String,String>} stored
 * on a Redis Stream record. The {@code payload} is encoded as a JSON string.
 */
@Component
public class EventEnvelopeCodec {

	private static final TypeReference<Map<String, Object>> PAYLOAD_TYPE = new TypeReference<>() {
	};

	// Self-contained mapper: payloads are plain JSON maps, so the codec owns its own
	// ObjectMapper rather than depending on a Spring-managed bean.
	private final ObjectMapper objectMapper = new ObjectMapper();

	public Map<String, String> toMap(EventEnvelope envelope) {
		Map<String, String> fields = new LinkedHashMap<>();
		fields.put("eventId", envelope.eventId());
		fields.put("type", envelope.type());
		fields.put("occurredAt", envelope.occurredAt().toString());
		fields.put("version", Integer.toString(envelope.version()));
		fields.put("payload", writePayload(envelope.payload()));
		return fields;
	}

	public EventEnvelope fromMap(Map<String, ?> fields) {
		return new EventEnvelope(
				String.valueOf(fields.get("eventId")),
				String.valueOf(fields.get("type")),
				Instant.parse(String.valueOf(fields.get("occurredAt"))),
				Integer.parseInt(String.valueOf(fields.get("version"))),
				readPayload(String.valueOf(fields.get("payload"))));
	}

	private String writePayload(Map<String, Object> payload) {
		try {
			return objectMapper.writeValueAsString(payload == null ? Map.of() : payload);
		}
		catch (JsonProcessingException ex) {
			throw new IllegalArgumentException("Failed to serialize event payload", ex);
		}
	}

	private Map<String, Object> readPayload(String json) {
		if (json == null || json.isBlank() || "null".equals(json)) {
			return Map.of();
		}
		try {
			return objectMapper.readValue(json, PAYLOAD_TYPE);
		}
		catch (JsonProcessingException ex) {
			throw new IllegalArgumentException("Failed to deserialize event payload", ex);
		}
	}
}
