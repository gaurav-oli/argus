package com.argus.conversation;

import java.util.List;

/**
 * The Ask-AI request body (Story 7.1): the full conversation so far. The last user message is the
 * new question; earlier turns give the model context for coherent follow-ups (AC #2). May be empty
 * or null — treated as an empty history.
 */
public record ChatRequest(List<ChatMessage> messages, Boolean deeper) {

	public List<ChatMessage> messagesOrEmpty() {
		return messages == null ? List.of() : messages;
	}

	/** True only when the client explicitly requested escalation; absent/null → false. */
	public boolean deeperRequested() {
		return Boolean.TRUE.equals(deeper);
	}
}
