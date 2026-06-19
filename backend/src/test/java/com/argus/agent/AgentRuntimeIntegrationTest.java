package com.argus.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.argus.TestcontainersConfiguration;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.connection.stream.PendingMessagesSummary;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * End-to-end: publish an event, the demo agent's consumer group receives it on a virtual
 * thread, and the message is acknowledged. Also verifies that a handler exception leaves the
 * message unacknowledged without crashing the runtime. Uses Testcontainers Redis (Docker).
 */
@SpringBootTest
@ActiveProfiles("dev")
@Import({ TestcontainersConfiguration.class, AgentRuntimeIntegrationTest.FailingAgentConfig.class })
class AgentRuntimeIntegrationTest {

	@Autowired
	AgentEventPublisher publisher;

	@Autowired
	DemoAgent demoAgent;

	@Autowired
	FailingAgent failingAgent;

	@Autowired
	StringRedisTemplate redis;

	@Test
	void publishedEventIsConsumedOnVirtualThreadAndAcked() throws InterruptedException {
		publisher.publish(DemoAgent.STREAM_KEY, "demo.event", Map.of("hello", "world"));

		assertTrue(demoAgent.latch().await(10, TimeUnit.SECONDS),
				"DemoAgent should receive the published event");

		EventEnvelope event = demoAgent.lastEvent();
		assertEquals("demo.event", event.type());
		assertEquals("world", event.payload().get("hello"));
		assertTrue(demoAgent.handledOnVirtualThread(), "agent handling should run on a virtual thread");

		assertEquals(0L, awaitPendingZero(), "consumer group should have acknowledged the message");
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

	private long awaitPendingZero() throws InterruptedException {
		long total = Long.MAX_VALUE;
		for (int i = 0; i < 50 && total != 0; i++) {
			PendingMessagesSummary summary = redis.opsForStream()
					.pending(DemoAgent.STREAM_KEY, demoAgent.consumerGroup());
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

	@TestConfiguration
	static class FailingAgentConfig {

		@Bean
		FailingAgent failingAgent() {
			return new FailingAgent();
		}
	}
}
