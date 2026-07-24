package com.argus.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import com.argus.common.BadRequestException;
import com.argus.common.PayloadTooLargeException;
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
		return propsWithTimeout(concurrency, Duration.ofMinutes(10));
	}

	private static ModelGatewayProperties propsWithTimeout(int concurrency, Duration callTimeout) {
		return new ModelGatewayProperties(concurrency, callTimeout, Duration.ofMinutes(10), "gemma3:27b", "unused");
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
	void generateInvokesHaikuFallbackOnBlankResponse() {
		// The local build can return HTTP 200 with empty content (Mini validation, 2026-07-16) —
		// treated the same as a thrown exception, not silently returned to the caller.
		AtomicInteger fallbackCalls = new AtomicInteger();
		HaikuFallback fallback = prompt -> {
			fallbackCalls.incrementAndGet();
			return "fallback-response";
		};

		ModelGateway gateway = new DefaultModelGateway(new MockChatModel(""), fallback, gov(), props(1));

		assertEquals("fallback-response", gateway.generate("ping"));
		assertEquals(1, fallbackCalls.get(), "Haiku fallback should be invoked exactly once on blank content");
	}

	@Test
	void generateInvokesHaikuFallbackOnWhitespaceOnlyResponse() {
		AtomicInteger fallbackCalls = new AtomicInteger();
		HaikuFallback fallback = prompt -> {
			fallbackCalls.incrementAndGet();
			return "fallback-response";
		};

		ModelGateway gateway = new DefaultModelGateway(new MockChatModel("   \n  "), fallback, gov(), props(1));

		assertEquals("fallback-response", gateway.generate("ping"));
		assertEquals(1, fallbackCalls.get());
	}

	@Test
	void smallTierDoesNotFallBackOnBlankResponse() {
		// Small tier (Agent 1/2/3 volume calls) propagates blank content as-is — no paid fallback,
		// same posture as its existing exception behavior (smallTierDoesNotFallBackToHaiku).
		AtomicInteger fallbackCalls = new AtomicInteger();
		HaikuFallback fallback = prompt -> {
			fallbackCalls.incrementAndGet();
			return "fallback";
		};
		ModelGateway gateway = new DefaultModelGateway(new MockChatModel(""), fallback, gov(), props(1));

		assertEquals("", gateway.generate("ping", ModelTier.SMALL));
		assertEquals(0, fallbackCalls.get());
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

	@Test
	void constructorRejectsNonPositiveConcurrency() {
		assertThrows(IllegalArgumentException.class,
				() -> new DefaultModelGateway(new MockChatModel("pong"), prompt -> "unused", gov(), props(0)));
		assertThrows(IllegalArgumentException.class,
				() -> new DefaultModelGateway(new MockChatModel("pong"), prompt -> "unused", gov(), props(-1)));
	}

	@Test
	void generateRejectsNullOrBlankPrompt() {
		ModelGateway gateway = new DefaultModelGateway(new MockChatModel("pong"), prompt -> "unused", gov(), props(1));

		assertThrows(BadRequestException.class, () -> gateway.generate(null));
		assertThrows(BadRequestException.class, () -> gateway.generate("   "));
		assertThrows(BadRequestException.class, () -> gateway.escalate(""));
	}

	@Test
	void generateRejectsOversizedPrompt() {
		ModelGateway gateway = new DefaultModelGateway(new MockChatModel("pong"), prompt -> "unused", gov(), props(1));
		String huge = "x".repeat(50_001);

		assertThrows(PayloadTooLargeException.class, () -> gateway.generate(huge));
	}

	@Test
	void haikuFailureAfterPrimaryFailureIsNotRetriedAndPropagates() {
		// Error-contract fix: a real Haiku failure (bad key, Anthropic outage) must surface as its
		// own exception, not get caught by the primary-model catch-all and silently retried against
		// Haiku a second time (which would double-bill and mislabel the log as "primary failed").
		AtomicInteger fallbackCalls = new AtomicInteger();
		HaikuFallback fallback = prompt -> {
			fallbackCalls.incrementAndGet();
			throw new ModelGatewayException("Haiku is down");
		};
		ModelGateway gateway = new DefaultModelGateway(new FailingChatModel(), fallback, gov(), props(1));

		assertThrows(ModelGatewayException.class, () -> gateway.generate("ping"));
		assertEquals(1, fallbackCalls.get(), "a failing Haiku fallback must be invoked exactly once, never retried");
	}

	@Test
	void haikuFailureAfterBlankPrimaryResponseIsNotRetriedAndPropagates() {
		AtomicInteger fallbackCalls = new AtomicInteger();
		HaikuFallback fallback = prompt -> {
			fallbackCalls.incrementAndGet();
			throw new ModelGatewayException("Haiku is down");
		};
		ModelGateway gateway = new DefaultModelGateway(new MockChatModel(""), fallback, gov(), props(1));

		assertThrows(ModelGatewayException.class, () -> gateway.generate("ping"));
		assertEquals(1, fallbackCalls.get(), "a failing Haiku fallback must be invoked exactly once, never retried");
	}

	@Test
	void permitIsReleasedEvenWhenHaikuFallbackFails() {
		HaikuFallback fallback = prompt -> {
			throw new ModelGatewayException("Haiku is down");
		};
		DefaultModelGateway gateway = new DefaultModelGateway(new FailingChatModel(), fallback, gov(), props(1));

		assertThrows(ModelGatewayException.class, () -> gateway.generate("ping"));
		assertEquals(1, gateway.availablePermits(), "permit must be released before the fallback call, not leaked on fallback failure");
	}

	@Test
	void generateFallsBackToHaikuWhenModelCallHangsPastTheTimeout() {
		// The crux of the hardening fix: previously an unbounded permits.acquire() + no call timeout
		// meant a genuinely stuck model call held the single permit forever and starved every queued
		// caller. Now the caller gets an answer (via Haiku) within the configured timeout regardless —
		// the abandoned background call is left to finish on its own (3s, well past the 200ms timeout
		// below) rather than forcibly cancelled (Spring AI's synchronous ChatClient has no cancel hook).
		AtomicInteger fallbackCalls = new AtomicInteger();
		HaikuFallback fallback = prompt -> {
			fallbackCalls.incrementAndGet();
			return "fallback-response";
		};
		DefaultModelGateway gateway = new DefaultModelGateway(
				new HangingChatModel(Duration.ofSeconds(3)), fallback, gov(), propsWithTimeout(1, Duration.ofMillis(200)));

		long start = System.nanoTime();
		String result = gateway.generate("ping");
		long elapsedMs = (System.nanoTime() - start) / 1_000_000;

		assertEquals("fallback-response", result);
		assertEquals(1, fallbackCalls.get());
		assertTrue(elapsedMs < 2500, "caller should get an answer near the ~200ms timeout, not wait for the 3s hang; took " + elapsedMs + "ms");
		assertEquals(1, gateway.availablePermits(), "permit must be released even when the underlying call is abandoned, not just when it completes");
	}

	/** A ChatModel whose call() blocks for {@code hangFor} before returning — simulates a genuinely
	 * stuck/slow call (as opposed to FailingChatModel's immediate throw or MockChatModel's instant
	 * blank response) to exercise the call-timeout path specifically. */
	private static final class HangingChatModel implements ChatModel {
		private final Duration hangFor;

		HangingChatModel(Duration hangFor) {
			this.hangFor = hangFor;
		}

		@Override
		public ChatResponse call(Prompt prompt) {
			try {
				Thread.sleep(hangFor.toMillis());
			}
			catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
			return new ChatResponse(List.of(new Generation(new AssistantMessage("too-late"))));
		}

		@Override
		public ChatOptions getDefaultOptions() {
			return ChatOptions.builder().build();
		}
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
