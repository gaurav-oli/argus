package com.argus.conversation;

import com.argus.recommendation.Recommendation;
import com.argus.recommendation.RecommendationService;
import java.util.List;
import java.util.Locale;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Ask AI about a recommendation (Story 7.1, FR-30). Session-gated under {@code /api/recommendations}
 * (the {@code SessionAuthFilter} gates everything not allowlisted). The answer is grounded in the
 * recommendation's diagnostic + the user's portfolio and produced via the Model Gateway. An unknown
 * id → RFC 9457 404; unauthenticated → 401 (handled by the filter).
 */
@RestController
@RequestMapping("/api/recommendations")
public class ConversationController {

	/** Per-message and whole-thread character caps so one request can't tie up the serialized BIG model. */
	private static final int MAX_MESSAGE_CHARS = 4_000;
	private static final int MAX_TOTAL_CHARS = 16_000;

	private final RecommendationService recommendations;
	private final ConversationService conversation;

	public ConversationController(RecommendationService recommendations, ConversationService conversation) {
		this.recommendations = recommendations;
		this.conversation = conversation;
	}

	@PostMapping("/{id}/chat")
	public ChatMessage chat(@PathVariable Long id, @RequestBody ChatRequest request) {
		List<ChatMessage> messages = validate(request.messagesOrEmpty());
		Recommendation rec = recommendations.diagnostic(id)
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
		String answer = conversation.askAboutRecommendation(rec, messages);
		return new ChatMessage("assistant", answer);
	}

	/**
	 * Reject malformed threads before a serialized BIG-model call: every role must be user/assistant,
	 * content within the size caps, and the final turn a non-blank user question.
	 */
	private static List<ChatMessage> validate(List<ChatMessage> messages) {
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
