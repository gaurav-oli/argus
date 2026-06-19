package com.argus.model;

import java.util.List;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;

/**
 * Deterministic small/mock {@link ChatModel} used by the {@code dev} profile so the
 * gateway works on the laptop with no Ollama and no network. Returns a fixed canned
 * response (good enough for the skeleton and for deterministic tests).
 */
public class MockChatModel implements ChatModel {

	private final String cannedResponse;

	public MockChatModel(String cannedResponse) {
		this.cannedResponse = cannedResponse;
	}

	@Override
	public ChatResponse call(Prompt prompt) {
		return new ChatResponse(List.of(new Generation(new AssistantMessage(cannedResponse))));
	}

	@Override
	public ChatOptions getDefaultOptions() {
		return ChatOptions.builder().build();
	}
}
