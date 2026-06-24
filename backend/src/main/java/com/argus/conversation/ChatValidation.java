package com.argus.conversation;

import java.util.List;
import java.util.Locale;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * Shared request validation for the Ask-AI chat endpoints (Stories 7.1/7.2). Rejects malformed
 * threads before a serialized BIG-model call: every role must be user/assistant, content within the
 * size caps, and the final turn a non-blank user question. Both the recommendation chat and the
 * portfolio chat validate identically.
 */
final class ChatValidation {

	/** Per-message and whole-thread character caps so one request can't tie up the serialized BIG model. */
	static final int MAX_MESSAGE_CHARS = 4_000;
	static final int MAX_TOTAL_CHARS = 16_000;

	private ChatValidation() {
	}

	static List<ChatMessage> validate(List<ChatMessage> messages) {
		if (messages.isEmpty()) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Ask a question to start the chat.");
		}
		long total = 0;
		for (ChatMessage m : messages) {
			String role = m.role() == null ? "" : m.role().toLowerCase(Locale.ROOT);
			if (!role.equals("user") && !role.equals("assistant")) {
				throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
						"Each message role must be 'user' or 'assistant'.");
			}
			int length = m.content() == null ? 0 : m.content().length();
			if (length > MAX_MESSAGE_CHARS) {
				throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "A message is too long.");
			}
			total += length;
		}
		if (total > MAX_TOTAL_CHARS) {
			throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "The conversation is too long.");
		}
		ChatMessage last = messages.get(messages.size() - 1);
		if (!"user".equalsIgnoreCase(last.role()) || last.content() == null || last.content().isBlank()) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
					"The last message must be a non-empty question from you.");
		}
		return messages;
	}
}
