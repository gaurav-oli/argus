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
	private final Semaphore permits;

	public DefaultModelGateway(ChatModel chatModel, HaikuFallback haikuFallback,
			ModelGatewayProperties properties) {
		this.chatClient = ChatClient.builder(chatModel).build();
		this.haikuFallback = haikuFallback;
		this.permits = new Semaphore(properties.concurrency());
	}

	@Override
	public String generate(String prompt, ModelTier tier) {
		return (tier == ModelTier.SMALL) ? generateSmall(prompt) : generateBig(prompt);
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

	/** Big-tier: serialized at concurrency 1 (Decision 1), Haiku fallback on failure. */
	private String generateBig(String prompt) {
		boolean acquired = false;
		try {
			permits.acquire();
			acquired = true;
			long startNanos = System.nanoTime();
			String content = chatClient.prompt().user(prompt).call().content();
			long durationMs = (System.nanoTime() - startNanos) / 1_000_000;
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
