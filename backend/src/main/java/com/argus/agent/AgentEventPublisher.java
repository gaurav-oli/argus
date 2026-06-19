package com.argus.agent;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * The single path for emitting events onto a Redis Stream. Always wraps the payload in a
 * complete {@link EventEnvelope} (generates {@code eventId}/{@code occurredAt}/{@code version}),
 * so the standard envelope is enforced for every published event.
 */
@Component
public class AgentEventPublisher {

	static final int ENVELOPE_VERSION = 1;

	private final StringRedisTemplate redis;
	private final EventEnvelopeCodec codec;

	public AgentEventPublisher(StringRedisTemplate redis, EventEnvelopeCodec codec) {
		this.redis = redis;
		this.codec = codec;
	}

	/** Build the envelope for {@code type}/{@code payload} and XADD it to {@code streamKey}. */
	public RecordId publish(String streamKey, String type, Map<String, Object> payload) {
		EventEnvelope envelope = new EventEnvelope(
				UUID.randomUUID().toString(),
				type,
				Instant.now(),
				ENVELOPE_VERSION,
				payload == null ? Map.of() : payload);
		return redis.opsForStream().add(streamKey, codec.toMap(envelope));
	}
}
