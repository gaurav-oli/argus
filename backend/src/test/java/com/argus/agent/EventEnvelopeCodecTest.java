package com.argus.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Plain unit test (no Spring/Docker) for envelope <-> Redis map round-tripping. */
class EventEnvelopeCodecTest {

	private final EventEnvelopeCodec codec = new EventEnvelopeCodec();

	@Test
	void roundTripPreservesAllFields() {
		EventEnvelope original = new EventEnvelope(
				"id-1", "demo.event", Instant.parse("2026-06-18T12:00:00Z"), 1,
				Map.of("ticker", "AAPL", "score", 42));

		Map<String, String> map = codec.toMap(original);
		assertEquals(Set.of("eventId", "type", "occurredAt", "version", "payload"), map.keySet(),
				"all five envelope fields must be present");

		EventEnvelope back = codec.fromMap(map);
		assertEquals("id-1", back.eventId());
		assertEquals("demo.event", back.type());
		assertEquals(original.occurredAt(), back.occurredAt());
		assertEquals(1, back.version());
		assertEquals("AAPL", back.payload().get("ticker"));
		assertEquals(42, ((Number) back.payload().get("score")).intValue());
	}

	@Test
	void emptyPayloadRoundTrips() {
		EventEnvelope e = new EventEnvelope("id-2", "x.y", Instant.now(), 1, Map.of());
		assertTrue(codec.fromMap(codec.toMap(e)).payload().isEmpty());
	}
}
