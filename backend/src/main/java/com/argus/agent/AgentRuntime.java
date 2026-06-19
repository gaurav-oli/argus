package com.argus.agent;

import jakarta.annotation.PostConstruct;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Drives all {@link Agent} beans: creates each agent's consumer group on startup, then on a
 * scheduled tick (virtual thread when {@code spring.threads.virtual.enabled=true}) reads new
 * messages for each agent's group, dispatches to {@link Agent#handle}, and acknowledges on
 * success. A handler exception is logged and the message is left pending (no poison-loop, since
 * {@code lastConsumed} does not redeliver). The scheduled method never throws — the runtime
 * does not crash on agent errors.
 */
@Component
public class AgentRuntime {

	private static final Logger log = LoggerFactory.getLogger(AgentRuntime.class);

	private final StringRedisTemplate redis;
	private final EventEnvelopeCodec codec;
	private final AgentProperties properties;
	private final List<Agent> agents;

	public AgentRuntime(StringRedisTemplate redis, EventEnvelopeCodec codec,
			AgentProperties properties, List<Agent> agents) {
		this.redis = redis;
		this.codec = codec;
		this.properties = properties;
		this.agents = agents;
	}

	@PostConstruct
	void createConsumerGroups() {
		for (Agent agent : agents) {
			try {
				redis.opsForStream().createGroup(agent.streamKey(), ReadOffset.latest(), agent.consumerGroup());
				log.info("Created consumer group '{}' on stream '{}'", agent.consumerGroup(), agent.streamKey());
			}
			catch (Exception ex) {
				// BUSYGROUP — group already exists; idempotent startup.
				log.debug("Consumer group '{}' already exists on '{}'", agent.consumerGroup(), agent.streamKey());
			}
		}
	}

	@Scheduled(fixedDelayString = "${argus.agent.poll-interval-ms:500}")
	void poll() {
		for (Agent agent : agents) {
			try {
				pollAgent(agent);
			}
			catch (Exception ex) {
				log.error("Polling failed for agent '{}'", agent.name(), ex);
			}
		}
	}

	private void pollAgent(Agent agent) {
		List<MapRecord<String, Object, Object>> records = redis.opsForStream().read(
				Consumer.from(agent.consumerGroup(), agent.name()),
				StreamReadOptions.empty().count(properties.readCount()),
				StreamOffset.create(agent.streamKey(), ReadOffset.lastConsumed()));
		if (records == null || records.isEmpty()) {
			return;
		}
		for (MapRecord<String, Object, Object> record : records) {
			dispatch(agent, record);
		}
	}

	private void dispatch(Agent agent, MapRecord<String, Object, Object> record) {
		EventEnvelope envelope;
		try {
			envelope = codec.fromMap(toStringMap(record.getValue()));
		}
		catch (RuntimeException ex) {
			log.error("Agent '{}' received an undecodable record {} (left pending)", agent.name(), record.getId(), ex);
			return;
		}
		try {
			agent.handle(envelope);
			redis.opsForStream().acknowledge(agent.streamKey(), agent.consumerGroup(), record.getId());
		}
		catch (Exception ex) {
			log.error("Agent '{}' failed handling event {} (left pending)", agent.name(), envelope.eventId(), ex);
		}
	}

	private Map<String, String> toStringMap(Map<Object, Object> raw) {
		return raw.entrySet().stream().collect(java.util.stream.Collectors.toMap(
				e -> String.valueOf(e.getKey()), e -> String.valueOf(e.getValue())));
	}
}
