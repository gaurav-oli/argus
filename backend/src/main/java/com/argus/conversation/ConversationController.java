package com.argus.conversation;

import com.argus.recommendation.Recommendation;
import com.argus.recommendation.RecommendationService;
import java.util.List;
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

	private final RecommendationService recommendations;
	private final ConversationService conversation;

	public ConversationController(RecommendationService recommendations, ConversationService conversation) {
		this.recommendations = recommendations;
		this.conversation = conversation;
	}

	@PostMapping("/{id}/chat")
	public ChatMessage chat(@PathVariable Long id, @RequestBody ChatRequest request) {
		List<ChatMessage> messages = ChatValidation.validate(request.messagesOrEmpty());
		Recommendation rec = recommendations.diagnostic(id)
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
		String answer = conversation.askAboutRecommendation(rec, messages);
		return new ChatMessage("assistant", answer);
	}
}
