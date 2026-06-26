package com.argus.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import com.argus.cost.CostEventRepository;
import com.argus.cost.CostGovernor;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;

/**
 * Plain unit tests (no Spring context, no Docker) for the gateway's routing,
 * fallback, and serialization behavior.
 */
class DefaultModelGatewayTest {

	private static ModelGatewayProperties props(int concurrency) {
		return new ModelGatewayProperties(concurrency, Duration.ofMinutes(10), "gemma3:27b", "unused");
	}

	/** Governor with a 0 budget = governance disabled (always allows paid calls), no repo touched. */
	private static CostGovernor gov() {
		return new CostGovernor(mock(CostEventRepository.class), 0);
	}

	@Test
	void generateReturnsModelContent() {
		ModelGateway gateway = new DefaultModelGateway(
				new MockChatModel("pong"), prompt -> "unused", gov(), props(1));

		assertEquals("pong", gateway.generate("ping"));
	}

	@Test
	void escalateRoutesDirectlyToHaiku() {
		ModelGateway gateway = new DefaultModelGateway(
				new MockChatModel("local"), prompt -> "haiku:" + prompt, gov(), props(1));

		assertEquals("haiku:deep question", gateway.escalate("deep question"));
	}

	@Test
	void generateInvokesHaikuFallbackOnPrimaryFailure() {
		AtomicInteger fallbackCalls = new AtomicInteger();
		HaikuFallback fallback = prompt -> {
			fallbackCalls.incrementAndGet();
			return "fallback-response";
		};

		ModelGateway gateway = new DefaultModelGateway(new FailingChatModel(), fallback, gov(), props(1));

		assertEquals("fallback-response", gateway.generate("ping"));
		assertEquals(1, fallbackCalls.get(), "Haiku fallback should be invoked exactly once on failure");
	}

	@Test
	void bigModelAccessIsSerialized() throws InterruptedException {
		ConcurrencyTrackingChatModel model = new ConcurrencyTrackingChatModel();
		DefaultModelGateway gateway = new DefaultModelGateway(model, (HaikuFallback) prompt -> "unused", gov(), props(1));

		int threads = 8;
		var workers = new Thread[threads];
		for (int i = 0; i < threads; i++) {
			workers[i] = new Thread(() -> gateway.generate("ping"));
		}
		for (Thread w : workers) {
			w.start();
		}
		for (Thread w : workers) {
			w.join();
		}

		assertEquals(1, model.maxConcurrent.get(), "concurrency=1 must serialize model access");
		assertTrue(gateway.availablePermits() == 1, "permit must be released after each call");
	}

	@Test
	void smallTierBypassesTheBigModelSemaphore() {
		ConcurrencyTrackingChatModel model = new ConcurrencyTrackingChatModel();
		DefaultModelGateway gateway = new DefaultModelGateway(model, (HaikuFallback) prompt -> "unused", gov(), props(1));

		int threads = 6;
		var workers = new Thread[threads];
		for (int i = 0; i < threads; i++) {
			workers[i] = new Thread(() -> gateway.generate("ping", ModelTier.SMALL));
		}
		for (Thread w : workers) {
			w.start();
		}
		for (Thread w : workers) {
			try {
				w.join();
			}
			catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		}

		assertTrue(model.maxConcurrent.get() > 1, "small-tier calls must run in parallel, not serialized");
		assertEquals(1, gateway.availablePermits(), "small tier must not consume big-model permits");
	}

	@Test
	void smallTierDoesNotFallBackToHaiku() {
		AtomicInteger fallbackCalls = new AtomicInteger();
		HaikuFallback fallback = prompt -> {
			fallbackCalls.incrementAndGet();
			return "fallback";
		};
		ModelGateway gateway = new DefaultModelGateway(new FailingChatModel(), fallback, gov(), props(1));

		try {
			gateway.generate("ping", ModelTier.SMALL);
		}
		catch (RuntimeException expected) {
			// small tier propagates failure rather than paying for a Haiku fallback
		}
		assertEquals(0, fallbackCalls.get(), "small tier must never invoke the paid Haiku fallback");
	}

	/** A ChatModel whose call() always throws, to exercise the fallback path. */
	private static final class FailingChatModel implements ChatModel {
		@Override
		public ChatResponse call(Prompt prompt) {
			throw new IllegalStateException("primary model boom");
		}

		@Override
		public ChatOptions getDefaultOptions() {
			return ChatOptions.builder().build();
		}
	}

	/** Records the maximum number of threads simultaneously inside call(). */
	private static final class ConcurrencyTrackingChatModel implements ChatModel {
		final AtomicInteger current = new AtomicInteger();
		final AtomicInteger maxConcurrent = new AtomicInteger();

		@Override
		public ChatResponse call(Prompt prompt) {
			int now = current.incrementAndGet();
			maxConcurrent.accumulateAndGet(now, Math::max);
			try {
				Thread.sleep(20);
			}
			catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
			finally {
				current.decrementAndGet();
			}
			return new ChatResponse(List.of(new Generation(new AssistantMessage("ok"))));
		}

		@Override
		public ChatOptions getDefaultOptions() {
			return ChatOptions.builder().build();
		}
	}
}
