package com.argus.config;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;

class StompDestinationGuardTest {

	private final StompDestinationGuard guard = new StompDestinationGuard();

	@Test
	void rawQueuePathsAreBlocked() {
		assertTrue(StompDestinationGuard.isRawQueueDestination("/queue"));
		assertTrue(StompDestinationGuard.isRawQueueDestination("/queue/portfolio-user1"));
		assertTrue(StompDestinationGuard.isRawQueueDestination("/queue/portfolio-user42"));
		assertFalse(StompDestinationGuard.isRawQueueDestination("/user/queue/portfolio"));
		assertFalse(StompDestinationGuard.isRawQueueDestination("/topic/demo"));
		assertFalse(StompDestinationGuard.isRawQueueDestination(null));
	}

	@Test
	void subscribeToRawPortfolioQueueThrows() {
		StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
		accessor.setDestination("/queue/portfolio-user7");
		accessor.setSessionId("s1");
		var message = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());

		assertThrows(IllegalArgumentException.class, () -> guard.preSend(message, null));
	}

	@Test
	void subscribeToUserQueueIsAllowed() {
		StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
		accessor.setDestination("/user/queue/portfolio");
		accessor.setSessionId("s1");
		var message = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());

		assertTrue(guard.preSend(message, null) == message);
	}
}
