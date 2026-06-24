package com.argus.conversation;

import java.util.List;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Ask AI about the whole portfolio (Story 7.2, FR-31). Session-gated under {@code /api/portfolio}
 * (the {@code SessionAuthFilter} gates everything not allowlisted). The answer is grounded in
 * holdings + health + upcoming calendar + recent recommendations + investor profile, via the Model
 * Gateway. Unauthenticated → 401; malformed thread → 400/413 (RFC 9457).
 */
@RestController
@RequestMapping("/api/portfolio")
public class PortfolioChatController {

	private final ConversationService conversation;

	public PortfolioChatController(ConversationService conversation) {
		this.conversation = conversation;
	}

	@PostMapping("/chat")
	public ChatMessage chat(@RequestBody ChatRequest request) {
		List<ChatMessage> messages = ChatValidation.validate(request.messagesOrEmpty());
		return new ChatMessage("assistant", conversation.askAboutPortfolio(messages));
	}
}
