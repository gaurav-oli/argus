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

	/**
	 * Push to only ONE person's own open connection(s) (Phase 2, multi-user) — for personal content
	 * like their live portfolio value, which must never reach anyone else's browser. Delivered to
	 * {@code /user/queue/<destination>} on the client; silently dropped if that person has no
	 * connection open right now (nothing to do, not an error) or {@code userId} is null.
	 */
	public void publishToUser(Long userId, String destination, Object payload) {
		if (userId == null) {
			return;
		}
		messagingTemplate.convertAndSendToUser(userId.toString(), destination, payload);
	}
}
