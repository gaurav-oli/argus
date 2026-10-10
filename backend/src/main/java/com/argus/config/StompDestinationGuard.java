package com.argus.config;

import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;

/**
 * Blocks client SUBSCRIBE (and SEND) to raw broker {@code /queue/**} destinations (S-A1).
 *
 * <p>Personal portfolio pushes use {@code convertAndSendToUser} → broker destination
 * {@code /queue/portfolio-user\{id\}}. Clients must SUBSCRIBE to {@code /user/queue/portfolio}
 * only; a stranger who guessed sequential ids must not SUBSCRIBE to the raw queue name.
 */
public final class StompDestinationGuard implements ChannelInterceptor {

	@Override
	public Message<?> preSend(Message<?> message, MessageChannel channel) {
		StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
		if (accessor == null) {
			return message;
		}
		StompCommand command = accessor.getCommand();
		if (command != StompCommand.SUBSCRIBE && command != StompCommand.SEND) {
			return message;
		}
		String destination = accessor.getDestination();
		if (isRawQueueDestination(destination)) {
			throw new IllegalArgumentException(
					"STOMP " + command + " to raw /queue destinations is not allowed; use /user/queue/…");
		}
		return message;
	}

	/** {@code /queue/...} including {@code /queue/portfolio-user42}; not {@code /user/queue/...}. */
	static boolean isRawQueueDestination(String destination) {
		if (destination == null || destination.isBlank()) {
			return false;
		}
		return destination.equals("/queue") || destination.startsWith("/queue/");
	}
}
