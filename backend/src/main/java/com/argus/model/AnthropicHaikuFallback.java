package com.argus.model;

import com.argus.cost.CostRecorder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;

/**
 * Real Claude Haiku fallback/escalation (Story 7.3, FR-32). Calls Haiku via a Spring AI
 * {@link ChatClient} built over the Anthropic model and records the per-call cost from the response
 * token usage. Constructed only when an API key is present (see {@link HaikuConfig}); the wiring is
 * the single LLM home — callers reach it only through the {@link ModelGateway}.
 */
public class AnthropicHaikuFallback implements HaikuFallback {

	private static final Logger log = LoggerFactory.getLogger(AnthropicHaikuFallback.class);

	private final ChatClient chatClient;
	private final CostRecorder costRecorder;
	private final String model;

	public AnthropicHaikuFallback(ChatClient chatClient, CostRecorder costRecorder, String model) {
		this.chatClient = chatClient;
		this.costRecorder = costRecorder;
		this.model = model;
	}

	@Override
	public String generate(String prompt) {
		ChatResponse response;
		try {
			response = chatClient.prompt().user(prompt).call().chatResponse();
		}
		catch (RuntimeException ex) {
			throw new ModelGatewayException("Claude Haiku call failed", ex);
		}
		if (response == null) {
			throw new ModelGatewayException("Claude Haiku returned no response");
		}
		recordCost(response.getMetadata());
		String text = extractText(response);
		// A billed call that returns no content (e.g. stopped before any output) — record the cost
		// (above) but give the user a clear message instead of an NPE/500.
		return (text == null || text.isBlank())
				? "I couldn't produce a deeper answer for that — try rephrasing or a shorter question."
				: text;
	}

	private void recordCost(ChatResponseMetadata metadata) {
		Usage usage = metadata == null ? null : metadata.getUsage();
		if (usage == null) {
			log.warn("Haiku response carried no usage metadata — recording cost as 0 (spend under-counted)");
			costRecorder.record(model, 0, 0);
			return;
		}
		costRecorder.record(model, tokens(usage.getPromptTokens()), tokens(usage.getCompletionTokens()));
	}

	private static String extractText(ChatResponse response) {
		if (response.getResult() == null || response.getResult().getOutput() == null) {
			return null;
		}
		return response.getResult().getOutput().getText();
	}

	private static long tokens(Number value) {
		return value == null ? 0L : value.longValue();
	}
}
