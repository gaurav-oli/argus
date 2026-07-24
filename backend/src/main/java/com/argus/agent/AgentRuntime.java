package com.argus.agent;

import jakarta.annotation.PostConstruct;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.RedisStreamCommands.XAddOptions;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.PendingMessage;
import org.springframework.data.redis.connection.stream.PendingMessages;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Drives all {@link Agent} beans: creates each agent's consumer group on startup, then on a
 * scheduled tick reads new messages for each agent's group, dispatches to {@link Agent#handle},
 * and acknowledges on success. A handler exception is logged and the message is left pending. The
 * scheduled method never throws — the runtime does not crash on agent errors.
 *
 * <p>Within one tick, every agent is polled on its own virtual thread ({@link #agentExecutor}, a
 * deliberately <b>unbounded</b> {@code newVirtualThreadPerTaskExecutor} — a bounded pool has
 * previously starved the fleet and collapsed recommendations to a single signal, see the agent
 * fleet/scheduler memory note) so one slow agent no longer blocks the others in iteration order.
 * {@code fixedDelay} on {@link #poll()} still guarantees ticks never overlap.
 *
 * <p>Crash recovery (Epic 1 hardening backlog — Story 1.5): a message delivered to a consumer but
 * never acknowledged (process crash or hung handler between {@code handle()} and {@code ack()})
 * previously sat in the pending-entries list forever, since {@link ReadOffset#lastConsumed()} only
 * ever returns new messages. {@link #reclaimPending} now reclaims (XCLAIM) anything idle past
 * {@code pelReclaimIdleMs} under the same consumer identity — {@code agent.name()} is stable
 * across restarts, so a restarted process naturally resumes as "the same consumer" — and
 * redispatches it through the normal {@link #dispatch} path, which also now dedupes by
 * {@code eventId} so a reclaim racing a slow-but-successful original handler doesn't re-run its
 * side effects, and rejects an envelope whose {@code version} this build doesn't understand.
 *
 * <p>Dead-lettering (Epic 1 hardening backlog — Story 1.5, "Retry/DLQ beyond the delivery-attempt
 * cap"): a message that exhausts {@code maxDeliveryAttempts} previously stayed pending forever —
 * quarantined but invisible except via raw {@code XPENDING}, and accumulating in the PEL on a 24/7
 * Mini indefinitely. {@link #reclaimPending} now claims it one last time, moves it (with failure
 * metadata) onto a per-agent dead-letter stream ({@code {streamKey}:dlq}), and acknowledges it off
 * the original stream — freeing the PEL while leaving a durable, inspectable record of what failed
 * and why. This covers all three "fails the same way every time" cases (poison/undecodable record,
 * unsupported envelope version, a handler bug that always throws) since each converges on the same
 * exhausted-delivery-count path. Manually requeuing a dead-lettered message back onto its source
 * stream remains a manual/operator action (e.g. {@code XADD} from the DLQ entry) — no automated
 * retry-from-DLQ exists, since nothing here can know whether the root cause has actually been fixed.
 */
@Component
public class AgentRuntime {

	private static final Logger log = LoggerFactory.getLogger(AgentRuntime.class);

	/** Same approximate-trim convention as {@link AgentEventPublisher} — bounds worst-case DLQ
	 * memory if a source misbehaves and floods it, without an exact trim on every write. */
	private static final long DLQ_MAX_STREAM_LENGTH = 10_000;
	private static final XAddOptions DLQ_TRIM_OPTIONS =
			XAddOptions.maxlen(DLQ_MAX_STREAM_LENGTH).approximateTrimming(true);

	private final StringRedisTemplate redis;
	private final EventEnvelopeCodec codec;
	private final AgentProperties properties;
	private final List<Agent> agents;
	private final ExecutorService agentExecutor = Executors.newVirtualThreadPerTaskExecutor();
	/** Message ids already logged as "exceeded max delivery attempts", so an exhausted message that
	 * stays idle-and-pending forever logs once instead of on every subsequent tick forever. In-memory
	 * only (resets on restart) — that's fine, a repeat log after a restart is harmless noise, not a
	 * correctness issue. */
	private final Set<String> exhaustedLogged = ConcurrentHashMap.newKeySet();

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
				if (isGroupAlreadyExists(ex)) {
					// BUSYGROUP — group already exists; idempotent startup.
					log.debug("Consumer group '{}' already exists on '{}'", agent.consumerGroup(), agent.streamKey());
				}
				else {
					// A real failure (Redis down, auth, wrong host) — surface it instead of
					// masking it as "already exists". The agent won't receive events until fixed.
					log.error("Failed to create consumer group '{}' on '{}' — agent '{}' will not receive events",
							agent.consumerGroup(), agent.streamKey(), agent.name(), ex);
				}
			}
		}
	}

	/** True only for Redis's BUSYGROUP ("group already exists") error, scanning the cause chain. */
	private static boolean isGroupAlreadyExists(Throwable ex) {
		for (Throwable t = ex; t != null; t = t.getCause()) {
			if (t.getMessage() != null && t.getMessage().contains("BUSYGROUP")) {
				return true;
			}
		}
		return false;
	}

	@Scheduled(fixedDelayString = "${argus.agent.poll-interval-ms:500}")
	void poll() {
		List<Future<?>> futures = new ArrayList<>();
		for (Agent agent : agents) {
			Runnable task = () -> pollAgentSafely(agent);
			futures.add(agentExecutor.submit(task));
		}
		for (Future<?> future : futures) {
			try {
				future.get();
			}
			catch (Exception ex) {
				log.error("Agent poll task failed unexpectedly", ex);
			}
		}
	}

	private void pollAgentSafely(Agent agent) {
		try {
			reclaimPending(agent);
			pollAgent(agent);
		}
		catch (Exception ex) {
			log.error("Polling failed for agent '{}'", agent.name(), ex);
		}
	}

	/** Reclaims and redispatches any message idle past {@code pelReclaimIdleMs} — see the class
	 * Javadoc. Best-effort: a failure here (e.g. Redis hiccup) is logged and skipped for this tick
	 * rather than aborting the agent's normal poll. */
	private void reclaimPending(Agent agent) {
		Duration idleThreshold = Duration.ofMillis(properties.pelReclaimIdleMs());
		PendingMessages pending;
		try {
			pending = redis.opsForStream().pending(agent.streamKey(),
					Consumer.from(agent.consumerGroup(), agent.name()), Range.unbounded(), properties.readCount());
		}
		catch (Exception ex) {
			log.warn("PEL check failed for agent '{}' (skipping reclaim this tick)", agent.name(), ex);
			return;
		}
		if (pending == null || pending.isEmpty()) {
			return;
		}
		for (PendingMessage m : pending) {
			if (m.getElapsedTimeSinceLastDelivery().compareTo(idleThreshold) < 0) {
				continue;
			}
			if (m.getTotalDeliveryCount() >= properties.maxDeliveryAttempts()) {
				// Exhausted: this message fails the same way every time (poison record, an envelope
				// version this build will never understand, a handler bug that always throws) — stop
				// reclaiming it and move it to the dead-letter stream instead of leaving it pending
				// (and growing the PEL) forever. Logged once (exhaustedLogged) even though
				// deadLetter() normally removes it from the PEL on the same tick — defends against a
				// failed dead-letter attempt (e.g. a Redis hiccup) being re-evaluated next tick without
				// repeating the "giving up" log every time.
				if (exhaustedLogged.add(agent.name() + '|' + m.getIdAsString())) {
					log.error("Agent '{}': message {} exceeded {} delivery attempts — moving to dead-letter stream",
							agent.name(), m.getIdAsString(), properties.maxDeliveryAttempts());
				}
				deadLetter(agent, m, idleThreshold);
				continue;
			}
			log.warn("Agent '{}': reclaiming message {} idle {} (attempt {}/{}) — likely left pending by a "
							+ "crash or hung handler",
					agent.name(), m.getIdAsString(), m.getElapsedTimeSinceLastDelivery(),
					m.getTotalDeliveryCount() + 1, properties.maxDeliveryAttempts());
			List<MapRecord<String, Object, Object>> reclaimed = redis.opsForStream()
					.claim(agent.streamKey(), agent.consumerGroup(), agent.name(), idleThreshold, m.getId());
			for (MapRecord<String, Object, Object> record : reclaimed) {
				dispatch(agent, record);
			}
		}
	}

	/** Claims an exhausted message one last time (to retrieve its content — {@link PendingMessage}
	 * carries only metadata), writes it to {@code {streamKey}:dlq} with failure metadata, and
	 * acknowledges it off the original stream. Best-effort: if claiming or the DLQ write fails (e.g.
	 * a Redis hiccup), the message is simply left pending and re-evaluated next tick rather than
	 * risking a silent data loss (claim-then-ack-fails would otherwise drop it with no DLQ record). */
	private void deadLetter(Agent agent, PendingMessage pending, Duration idleThreshold) {
		List<MapRecord<String, Object, Object>> claimed;
		try {
			claimed = redis.opsForStream().claim(
					agent.streamKey(), agent.consumerGroup(), agent.name(), idleThreshold, pending.getId());
		}
		catch (Exception ex) {
			log.warn("Agent '{}': failed to claim exhausted message {} for dead-lettering (will retry next tick)",
					agent.name(), pending.getIdAsString(), ex);
			return;
		}
		for (MapRecord<String, Object, Object> record : claimed) {
			try {
				Map<String, String> dlqFields = new LinkedHashMap<>(toStringMap(record.getValue()));
				dlqFields.put("_dlqOriginalStream", agent.streamKey());
				dlqFields.put("_dlqOriginalId", record.getId().toString());
				dlqFields.put("_dlqAgent", agent.name());
				dlqFields.put("_dlqDeliveryAttempts", String.valueOf(pending.getTotalDeliveryCount()));
				dlqFields.put("_dlqDeadLetteredAt", Instant.now().toString());
				redis.opsForStream().add(dlqStreamKey(agent), dlqFields, DLQ_TRIM_OPTIONS);
				redis.opsForStream().acknowledge(agent.streamKey(), agent.consumerGroup(), record.getId());
				log.error("Agent '{}': message {} moved to dead-letter stream '{}' after {} delivery attempts",
						agent.name(), record.getId(), dlqStreamKey(agent), pending.getTotalDeliveryCount());
			}
			catch (Exception ex) {
				log.warn("Agent '{}': failed to dead-letter claimed message {} (left pending, will retry next tick)",
						agent.name(), record.getId(), ex);
			}
		}
	}

	private static String dlqStreamKey(Agent agent) {
		return agent.streamKey() + ":dlq";
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
		if (envelope.version() != AgentEventPublisher.ENVELOPE_VERSION) {
			// Poison/quarantine posture: left pending for manual inspection rather than silently
			// processed by a consumer that doesn't understand this schema version, or silently
			// dropped — same convention as an undecodable record just above.
			log.error("Agent '{}' received event {} with unsupported envelope version {} (expected {}, left pending)",
					agent.name(), envelope.eventId(), envelope.version(), AgentEventPublisher.ENVELOPE_VERSION);
			return;
		}
		if (alreadyProcessed(agent, envelope)) {
			log.info("Agent '{}' skipping already-processed event {} (redelivered)", agent.name(), envelope.eventId());
			redis.opsForStream().acknowledge(agent.streamKey(), agent.consumerGroup(), record.getId());
			return;
		}
		try {
			agent.handle(envelope);
			markProcessed(agent, envelope);
			redis.opsForStream().acknowledge(agent.streamKey(), agent.consumerGroup(), record.getId());
		}
		catch (Exception ex) {
			log.error("Agent '{}' failed handling event {} (left pending)", agent.name(), envelope.eventId(), ex);
		}
	}

	/** Dedup guard (Epic 1 hardening backlog): a redelivery of an event that was already fully
	 * handled (crash landed between handle() succeeding and ack() running) shouldn't re-run side
	 * effects. Marked AFTER a successful handle(), not before — marking before would make a
	 * legitimately-reclaimed-but-never-completed event look "already done" and skip it forever. */
	private boolean alreadyProcessed(Agent agent, EventEnvelope envelope) {
		return Boolean.TRUE.equals(redis.hasKey(dedupeKey(agent, envelope)));
	}

	private void markProcessed(Agent agent, EventEnvelope envelope) {
		redis.opsForValue().set(dedupeKey(agent, envelope), "1", Duration.ofHours(properties.dedupeTtlHours()));
	}

	private String dedupeKey(Agent agent, EventEnvelope envelope) {
		return "argus:agent:" + agent.name() + ":dedup:" + envelope.eventId();
	}

	private Map<String, String> toStringMap(Map<Object, Object> raw) {
		return raw.entrySet().stream().collect(java.util.stream.Collectors.toMap(
				e -> String.valueOf(e.getKey()), e -> String.valueOf(e.getValue())));
	}
}
