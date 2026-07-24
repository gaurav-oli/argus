package com.argus.model;

import com.argus.common.BadRequestException;
import com.argus.common.PayloadTooLargeException;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.stereotype.Component;

/**
 * Default {@link ModelGateway}: wraps a Spring AI {@link ChatClient} built from the
 * active-profile {@link ChatModel}, serializes big-model access through a semaphore
 * (concurrency 1 per Decision 1), and routes to the {@link HaikuFallback} on failure.
 */
@Component
public class DefaultModelGateway implements ModelGateway {

	private static final Logger log = LoggerFactory.getLogger(DefaultModelGateway.class);

	/** Backstop against a runaway/buggy caller-assembled prompt, not a normal operating limit —
	 * grounded prompts (portfolio + calendar + persona context) legitimately run several thousand
	 * characters, far above the per-message chat caps ({@code ChatValidation.MAX_MESSAGE_CHARS}). */
	private static final int MAX_PROMPT_CHARS = 50_000;

	private final ChatClient chatClient;
	private final HaikuFallback haikuFallback;
	private final com.argus.cost.CostGovernor costGovernor;
	private final Semaphore permits;
	private final Duration callTimeout;

	public DefaultModelGateway(ChatModel chatModel, HaikuFallback haikuFallback,
			com.argus.cost.CostGovernor costGovernor, ModelGatewayProperties properties) {
		if (properties.concurrency() < 1) {
			// A 0/negative value would create a semaphore no caller can ever acquire, silently
			// bricking every BIG-tier call — fail loudly at startup instead.
			throw new IllegalArgumentException(
					"argus.model.concurrency must be >= 1, got " + properties.concurrency());
		}
		this.chatClient = ChatClient.builder(chatModel).build();
		this.haikuFallback = haikuFallback;
		this.costGovernor = costGovernor;
		this.permits = new Semaphore(properties.concurrency());
		this.callTimeout = properties.callTimeoutSeconds();
	}

	@Override
	public String generate(String prompt, ModelTier tier) {
		validatePrompt(prompt);
		return (tier == ModelTier.SMALL) ? generateSmall(prompt) : generateBig(prompt);
	}

	@Override
	public String escalate(String prompt) {
		validatePrompt(prompt);
		// Cost Governor (Agent 6, FR-46): once the monthly budget hits 95%, auto-switch escalations to
		// the local model instead of making another paid Haiku call.
		if (!costGovernor.allowPaidCall()) {
			log.info("Budget threshold reached — serving 'deeper analysis' from the local model, not Haiku");
			return generateBig(prompt);
		}
		// Explicit "deeper analysis": go to Haiku (not on the big-model semaphore). HaikuFallback throws
		// ModelGatewayException when unavailable → 503 at the edge.
		log.info("escalating to Haiku (deeper analysis)");
		return haikuFallback.generate(prompt);
	}

	/** Cheap guard against a null/blank prompt (caller bug) or an absurdly oversized one (runaway
	 * context assembly) reaching the model — both are caller-request problems (400/413), not the
	 * model "failing", so they're rejected before ever touching the semaphore or Haiku. */
	private static void validatePrompt(String prompt) {
		if (prompt == null || prompt.isBlank()) {
			throw new BadRequestException("Prompt must not be empty.");
		}
		if (prompt.length() > MAX_PROMPT_CHARS) {
			throw new PayloadTooLargeException("Prompt exceeds the " + MAX_PROMPT_CHARS + " character limit.");
		}
	}

	/**
	 * Small-tier: unserialized, no Haiku fallback. High-frequency agents (1/2/3) call this at
	 * volume, so a per-call paid fallback would blow the budget — failures propagate and the
	 * caller decides (e.g. default to neutral).
	 */
	private String generateSmall(String prompt) {
		long startNanos = System.nanoTime();
		String content = chatClient.prompt().user(prompt).call().content();
		log.debug("small model generate ok ({} ms)", (System.nanoTime() - startNanos) / 1_000_000);
		return content;
	}

	/**
	 * Big-tier: serialized at concurrency 1 (Decision 1), Haiku fallback on failure, on a blank
	 * response, <b>or now on a timeout</b> at two points:
	 * <ul>
	 *   <li>waiting for the permit itself ({@code tryAcquire} instead of an unbounded
	 *       {@code acquire()}) — a caller queued behind an already-stuck call no longer waits
	 *       forever; it gives up at {@code callTimeout} and tries Haiku instead.</li>
	 *   <li>the model call itself, raced on a bounded future — the local build in prod can
	 *       legitimately return HTTP 200 with empty content (a known-bad GGUF/template combination,
	 *       Mini validation 2026-07-16, exhausts its token budget on invisible tokens for a large
	 *       grounded prompt) or, in the worst case, never return at all if the call genuinely hangs.
	 *       Both are now bounded the same way blank-response already was.</li>
	 * </ul>
	 * The abandoned background call on a timeout isn't forcibly cancelled (Spring AI's synchronous
	 * {@code ChatClient} doesn't expose an interruptible handle) — it's left to finish or fail on its
	 * own and its result is discarded. That's an accepted gap: the goal here is bounding how long a
	 * caller (and everyone queued behind it) waits, not killing the underlying HTTP request.
	 */
	private String generateBig(String prompt) {
		boolean acquired = false;
		try {
			acquired = permits.tryAcquire(callTimeout.toMillis(), TimeUnit.MILLISECONDS);
			if (!acquired) {
				log.warn("Timed out after {} waiting for the model permit (still held by another call) — "
						+ "invoking Haiku fallback", callTimeout);
				return haikuFallback.generate(prompt);
			}
			long startNanos = System.nanoTime();
			String content = callWithTimeout(prompt);
			long durationMs = (System.nanoTime() - startNanos) / 1_000_000;
			if (content == null || content.isBlank()) {
				log.warn("Local model returned no usable content ({} ms, {} prompt chars) — "
						+ "invoking Haiku fallback", durationMs, prompt.length());
				return haikuFallback.generate(prompt);
			}
			log.info("model generate ok ({} ms)", durationMs);
			return content;
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new ModelGatewayException("Interrupted while awaiting model permit", ex);
		}
		catch (RuntimeException ex) {
			log.warn("Primary model failed — invoking Haiku fallback", ex);
			return haikuFallback.generate(prompt);
		}
		finally {
			if (acquired) {
				permits.release();
			}
		}
	}

	/** Races the real model call against {@link #callTimeout}; a timeout is treated as a failure
	 * (caught by {@link #generateBig}'s catch-all, which falls through to Haiku) rather than
	 * propagating {@link TimeoutException} directly, so callers see one consistent failure path. */
	private String callWithTimeout(String prompt) {
		CompletableFuture<String> future =
				CompletableFuture.supplyAsync(() -> chatClient.prompt().user(prompt).call().content());
		try {
			return future.get(callTimeout.toMillis(), TimeUnit.MILLISECONDS);
		}
		catch (TimeoutException ex) {
			throw new ModelGatewayException(
					"Model call exceeded " + callTimeout + " — falling back", ex);
		}
		catch (ExecutionException ex) {
			Throwable cause = ex.getCause();
			throw (cause instanceof RuntimeException re) ? re : new ModelGatewayException("Model call failed", cause);
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new ModelGatewayException("Interrupted while awaiting model call", ex);
		}
	}

	/** Visible for testing: permits currently available (== configured concurrency when idle). */
	int availablePermits() {
		return permits.availablePermits();
	}
}
