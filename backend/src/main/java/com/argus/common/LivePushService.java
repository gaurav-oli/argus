package com.argus.common;

import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

/**
 * Publishes messages to STOMP {@code /topic/...} destinations for live UI push.
 * The single seam through which the backend pushes to connected clients.
 */
@Service
public class LivePushService {

	private final SimpMessagingTemplate messagingTemplate;

	public LivePushService(SimpMessagingTemplate messagingTemplate) {
		this.messagingTemplate = messagingTemplate;
	}

	public void publish(String topic, Object payload) {
		messagingTemplate.convertAndSend(topic, payload);
	}
}
