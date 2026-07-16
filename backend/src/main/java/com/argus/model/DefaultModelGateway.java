package com.argus.model;

import java.util.concurrent.Semaphore;
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

	private final ChatClient chatClient;
	private final HaikuFallback haikuFallback;
	private final com.argus.cost.CostGovernor costGovernor;
	private final Semaphore permits;

	public DefaultModelGateway(ChatModel chatModel, HaikuFallback haikuFallback,
			com.argus.cost.CostGovernor costGovernor, ModelGatewayProperties properties) {
		this.chatClient = ChatClient.builder(chatModel).build();
		this.haikuFallback = haikuFallback;
		this.costGovernor = costGovernor;
		this.permits = new Semaphore(properties.concurrency());
	}

	@Override
	public String generate(String prompt, ModelTier tier) {
		return (tier == ModelTier.SMALL) ? generateSmall(prompt) : generateBig(prompt);
	}

	@Override
	public String escalate(String prompt) {
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
	 * Big-tier: serialized at concurrency 1 (Decision 1), Haiku fallback on failure <b>or on a blank
	 * response</b>. The local build in prod can legitimately return HTTP 200 with empty content — a
	 * known-bad GGUF/template combination (Mini validation, 2026-07-16) sometimes exhausts its entire
	 * token budget on invisible/special tokens for a large grounded prompt, producing no usable text
	 * even though the call itself "succeeded". Left unguarded, that reaches the user as a blank chat
	 * bubble with no error. Treating blank the same as a thrown exception closes that gap using the
	 * fallback path this method already has.
	 */
	private String generateBig(String prompt) {
		boolean acquired = false;
		try {
			permits.acquire();
			acquired = true;
			long startNanos = System.nanoTime();
			String content = chatClient.prompt().user(prompt).call().content();
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

	/** Visible for testing: permits currently available (== configured concurrency when idle). */
	int availablePermits() {
		return permits.availablePermits();
	}
}
