package com.argus.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.argus.TestcontainersConfiguration;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.connection.stream.PendingMessagesSummary;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

/**
 * End-to-end against real Redis (Testcontainers): publish an event, the demo agent's consumer
 * group receives it on a virtual thread, and the message is acknowledged. Also covers the Epic 1
 * hardening backlog additions to {@link AgentRuntime} (Story 1.5) — PEL crash recovery, dedup, and
 * envelope version rejection — plus the pre-existing guarantee that a handler exception leaves the
 * message unacknowledged without crashing the runtime.
 *
 * <p>{@code pel-reclaim-idle-ms} is overridden way down from its 300s production default so the
 * reclaim tests can observe it within a normal test timeout; this doesn't destabilize the other
 * tests in this class — a shorter threshold only means a still-pending message might be reclaimed
 * (and its handler re-invoked) sooner than it otherwise would, which the existing failure-path test
 * already tolerates (Redis's pending count reflects "still unacked", not "delivery attempts").
 */
@SpringBootTest
@ActiveProfiles("dev")
@Import({ TestcontainersConfiguration.class, AgentRuntimeIntegrationTest.FailingAgentConfig.class })
@TestPropertySource(properties = { "argus.agent.pel-reclaim-idle-ms=300", "argus.agent.poll-interval-ms=150" })
class AgentRuntimeIntegrationTest {

	@Autowired
	AgentEventPublisher publisher;

	@Autowired
	DemoAgent demoAgent;

	@Autowired
	FailingAgent failingAgent;

	@Autowired
	FlakyAgent flakyAgent;

	@Autowired
	CountingAgent countingAgent;

	@Autowired
	VersionMismatchAgent versionMismatchAgent;

	@Autowired
	StringRedisTemplate redis;

	@Autowired
	EventEnvelopeCodec codec;

	@Test
	void publishedEventIsConsumedOnVirtualThreadAndAcked() throws InterruptedException {
		publisher.publish(DemoAgent.STREAM_KEY, "demo.event", Map.of("hello", "world"));

		assertTrue(demoAgent.latch().await(10, TimeUnit.SECONDS),
				"DemoAgent should receive the published event");

		EventEnvelope event = demoAgent.lastEvent();
		assertEquals("demo.event", event.type());
		assertEquals("world", event.payload().get("hello"));
		assertTrue(demoAgent.handledOnVirtualThread(), "agent handling should run on a virtual thread");

		assertEquals(0L, awaitPendingZero(DemoAgent.STREAM_KEY, demoAgent.consumerGroup()),
				"consumer group should have acknowledged the message");
	}

	@Test
	void handlerExceptionLeavesMessageUnacknowledgedAndRuntimeKeepsRunning() throws InterruptedException {
		publisher.publish(FailingAgent.STREAM_KEY, "demo.event", Map.of("x", "y"));

		assertTrue(failingAgent.invoked().await(10, TimeUnit.SECONDS),
				"failing agent should receive (and then throw on) the event");

		// The message must NOT be acknowledged — it stays in the pending-entries list.
		// Give the (non-)ack path time to settle, then assert it is still pending.
		Thread.sleep(500);
		PendingMessagesSummary summary = redis.opsForStream()
				.pending(FailingAgent.STREAM_KEY, failingAgent.consumerGroup());
		assertEquals(1L, summary.getTotalPendingMessages(),
				"a failed event must remain unacknowledged (pending)");
	}

	@Test
	void crashRecoveryReclaimsAndRetriesAStuckMessage() throws InterruptedException {
		// FlakyAgent throws on its first delivery (simulating a crash/hang that left the message
		// pending, unacked) and succeeds on the next — proving reclaimPending() actually notices an
		// idle pending entry, XCLAIMs it, and redispatches it through the normal path.
		publisher.publish(FlakyAgent.STREAM_KEY, "demo.event", Map.of("x", "y"));

		assertTrue(flakyAgent.succeeded().await(10, TimeUnit.SECONDS),
				"the reclaimed redelivery should eventually succeed");
		assertTrue(flakyAgent.attempts().get() >= 2, "handle() must have been retried after the first failure");

		assertEquals(0L, awaitPendingZero(FlakyAgent.STREAM_KEY, flakyAgent.consumerGroup()),
				"the message must end up acknowledged once the retried handling succeeds");
	}

	@Test
	void alreadyProcessedEventIsSkippedButStillAcknowledged() throws InterruptedException {
		// Simulates the crash-between-handle()-succeeding-and-ack()-running race the dedup guard
		// protects against: pre-seed the exact dedupe key AgentRuntime would have written after a
		// (hypothetical) prior successful handling of this event id, then deliver it fresh and
		// confirm handle() is skipped — proving the guard actually prevents a re-run of side effects
		// on a redelivery, not just that it compiles.
		String eventId = "dedup-test-" + UUID.randomUUID();
		redis.opsForValue().set("argus:agent:counting-agent:dedup:" + eventId, "1", Duration.ofHours(1));

		EventEnvelope envelope = new EventEnvelope(eventId, "demo.event", Instant.now(), 1, Map.of("x", "y"));
		redis.opsForStream().add(CountingAgent.STREAM_KEY, codec.toMap(envelope));

		Thread.sleep(1000);

		assertEquals(0, countingAgent.invocations().get(), "handle() must not run for an already-processed event id");
		assertEquals(0L, awaitPendingZero(CountingAgent.STREAM_KEY, countingAgent.consumerGroup()),
				"a skipped-as-duplicate event must still be acknowledged so it doesn't stay pending forever");
	}

	@Test
	void unsupportedEnvelopeVersionIsLeftPendingAndNeverDispatched() throws InterruptedException {
		// A dedicated agent/stream, not CountingAgent — a version-mismatched record is permanently
		// unprocessable and (correctly, by design) stays pending forever, so sharing a stream with
		// another test would leak a permanently-pending message into that test's own pending-count
		// assertions.
		EventEnvelope wrongVersion = new EventEnvelope(
				"version-test-" + UUID.randomUUID(), "demo.event", Instant.now(), 999, Map.of("x", "y"));
		redis.opsForStream().add(VersionMismatchAgent.STREAM_KEY, codec.toMap(wrongVersion));

		Thread.sleep(1000);

		assertEquals(0, versionMismatchAgent.invocations().get(),
				"a record with an unsupported envelope version must never reach handle()");
		PendingMessagesSummary summary = redis.opsForStream()
				.pending(VersionMismatchAgent.STREAM_KEY, versionMismatchAgent.consumerGroup());
		assertEquals(1L, summary.getTotalPendingMessages(),
				"an unsupported-version record is quarantined (left pending), not silently dropped or acked");
	}

	private long awaitPendingZero(String streamKey, String consumerGroup) throws InterruptedException {
		long total = Long.MAX_VALUE;
		for (int i = 0; i < 50 && total != 0; i++) {
			PendingMessagesSummary summary = redis.opsForStream().pending(streamKey, consumerGroup);
			total = (summary == null) ? 0 : summary.getTotalPendingMessages();
			if (total != 0) {
				Thread.sleep(100);
			}
		}
		return total;
	}

	/** An agent that always throws, to exercise the runtime's error path (AC #5). */
	static class FailingAgent implements Agent {

		static final String STREAM_KEY = "argus:stream:failing";

		private final CountDownLatch invoked = new CountDownLatch(1);

		@Override
		public String name() {
			return "failing-agent";
		}

		@Override
		public String streamKey() {
			return STREAM_KEY;
		}

		@Override
		public void handle(EventEnvelope event) {
			invoked.countDown();
			throw new IllegalStateException("boom");
		}

		CountDownLatch invoked() {
			return invoked;
		}
	}

	/** Fails on its first delivery, succeeds from the second — simulates a crash that leaves a
	 * message pending, then a successful reclaimed retry. */
	static class FlakyAgent implements Agent {

		static final String STREAM_KEY = "argus:stream:flaky";

		private final AtomicInteger attempts = new AtomicInteger();
		private final CountDownLatch succeeded = new CountDownLatch(1);

		@Override
		public String name() {
			return "flaky-agent";
		}

		@Override
		public String streamKey() {
			return STREAM_KEY;
		}

		@Override
		public void handle(EventEnvelope event) {
			if (attempts.incrementAndGet() == 1) {
				throw new IllegalStateException("simulated crash on first delivery");
			}
			succeeded.countDown();
		}

		AtomicInteger attempts() {
			return attempts;
		}

		CountDownLatch succeeded() {
			return succeeded;
		}
	}

	/** Always succeeds and counts invocations — used to prove dedup/version-rejection skip handle()
	 * entirely, not merely that they fail gracefully. */
	static class CountingAgent implements Agent {

		static final String STREAM_KEY = "argus:stream:counting";

		private final AtomicInteger invocations = new AtomicInteger();

		@Override
		public String name() {
			return "counting-agent";
		}

		@Override
		public String streamKey() {
			return STREAM_KEY;
		}

		@Override
		public void handle(EventEnvelope event) {
			invocations.incrementAndGet();
		}

		AtomicInteger invocations() {
			return invocations;
		}
	}

	/** Same shape as {@link CountingAgent} but on its own stream — kept separate so the
	 * version-rejection test's permanently-pending poison record can't leak into another test's
	 * pending-count assertions. */
	static class VersionMismatchAgent implements Agent {

		static final String STREAM_KEY = "argus:stream:version-mismatch";

		private final AtomicInteger invocations = new AtomicInteger();

		@Override
		public String name() {
			return "version-mismatch-agent";
		}

		@Override
		public String streamKey() {
			return STREAM_KEY;
		}

		@Override
		public void handle(EventEnvelope event) {
			invocations.incrementAndGet();
		}

		AtomicInteger invocations() {
			return invocations;
		}
	}

	@TestConfiguration
	static class FailingAgentConfig {

		@Bean
		FailingAgent failingAgent() {
			return new FailingAgent();
		}

		@Bean
		FlakyAgent flakyAgent() {
			return new FlakyAgent();
		}

		@Bean
		CountingAgent countingAgent() {
			return new CountingAgent();
		}

		@Bean
		VersionMismatchAgent versionMismatchAgent() {
			return new VersionMismatchAgent();
		}
	}
}
